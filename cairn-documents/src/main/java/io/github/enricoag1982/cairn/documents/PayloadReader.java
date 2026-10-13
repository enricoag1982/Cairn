package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.NodeReader.Entry;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/**
 * Reads the YAML node tree of a transaction payload into a transaction, strictly, and reports every problem with its
 * location (capability {@code json-payloads}, design D1 to D3). The strict reading of nodes is {@link NodeReader}'s,
 * shared with schema documents; what is specific to payloads is here: the keys of writes, references, instants, conflict
 * checks, and the types of the values.
 *
 * <p>Values are read by the value type of their attribute, in the type that the write concerns. The reader remembers the
 * temporary IDs and the supplied IDs that earlier writes create with their types, and asks the {@link IdLookup} about
 * other IDs. A reader is used once. It reads as much as it can after a problem, using defaults for what it could not
 * read, so that one pass finds every problem; the transaction it builds is returned only if there is none.
 *
 * <p>The path of a problem is {@code @transaction}, or {@code @write.<position>} for a problem in a write, followed by
 * {@code .<attribute>} for one about the value of an attribute, as the engine writes the paths of its problems. Every
 * problem carries its location in the text as well.
 */
final class PayloadReader {

    private static final String TRANSACTION_PATH = PayloadKeys.TRANSACTION_PATH;
    private static final Ref NO_REFERENCE = new EntityId("");
    private static final String INSTANT_EXAMPLE = "2026-01-01T00:00:00Z";

    /** What a reference names: an entity or a relationship, and the name of its type. */
    private record Subject(boolean relationship, String typeName) {
    }

    /** A reference as read: the reference, the text that problems call it, and what it names if that is known. */
    private record Reference(Ref ref, String text, Optional<Subject> subject) {
    }

    /** Finds the attribute of a type by name or alias. */
    @FunctionalInterface
    private interface Attributes {
        Optional<Attribute> find(String name);
    }

    private static final Attributes NO_ATTRIBUTES = name -> Optional.empty();

    /** The location data of the write that is being read. */
    private static final class Located {
        private final PayloadLocations.Kind kind;
        private final DocumentLocation location;
        private final List<String> own = new ArrayList<>();
        private final List<String> mentions = new ArrayList<>();
        private final Map<String, DocumentLocation> attributes = new HashMap<>();

        Located(PayloadLocations.Kind kind, DocumentLocation location) {
            this.kind = kind;
            this.location = location;
        }

        PayloadLocations.WriteEntry entry() {
            return new PayloadLocations.WriteEntry(kind, location, own, mentions, attributes);
        }
    }

    private final NodeReader nodes = new NodeReader();
    private final Schema schema;
    private final IdLookup lookup;
    /** What the writes read so far create: by the text of their temporary ID, as in {@code $acme}, and of their ID. */
    private final Map<String, Subject> created = new HashMap<>();
    private final List<PayloadLocations.WriteEntry> entries = new ArrayList<>();

    private PayloadReader(Schema schema, IdLookup lookup) {
        this.schema = schema;
        this.lookup = lookup;
    }

    /** Reads a transaction payload. */
    static PayloadReadResult read(String text, Schema schema, IdLookup lookup) {
        return switch (YamlTree.compose(text)) {
            case YamlTree.Failed failed ->
                    new PayloadReadResult.Invalid(List.of(NodeReader.syntax(failed, TRANSACTION_PATH)));
            case YamlTree.Composed composed -> new PayloadReader(schema, lookup).readPayload(composed);
        };
    }

    // ------------------------------------------------------------------ the payload

    private PayloadReadResult readPayload(YamlTree.Composed composed) {
        Optional<Problem> unsupported = composed.root().flatMap(
                root -> NodeReader.unsupportedVersion(root, TRANSACTION_PATH, "transaction payloads", "payload"));
        if (unsupported.isPresent()) {
            return new PayloadReadResult.Invalid(List.of(unsupported.get()));
        }
        DocumentLocation rootLocation =
                composed.root().map(root -> NodeReader.location("", root)).orElse(new DocumentLocation("", 1, 1));
        nodes.addAll(YamlSubset.problems(composed, "a transaction payload", PayloadReader::elementPath));
        if (composed.root().isEmpty()) {
            nodes.missing(PayloadKeys.TRANSACTION_REQUIRED, "the transaction payload", TRANSACTION_PATH, rootLocation);
            return invalid();
        }
        Optional<Map<String, Entry>> top = nodes.properties(composed.root().get(), "", TRANSACTION_PATH,
                "the transaction payload", PayloadKeys.TRANSACTION, PayloadKeys.TRANSACTION_REQUIRED);
        if (top.isEmpty()) {
            return invalid();
        }
        Map<String, Entry> p = top.get();
        nodes.formatVersion(p.get("cairn"), TRANSACTION_PATH);
        nodes.string(p.get(Keys.SCHEMA), TRANSACTION_PATH, "value of '$schema'");
        String principal = nodes.string(p.get("principal"), TRANSACTION_PATH, "value of 'principal'").orElse("");
        Optional<String> onBehalfOf = nodes.string(p.get("onBehalfOf"), TRANSACTION_PATH, "value of 'onBehalfOf'");
        Optional<String> reason = nodes.string(p.get("reason"), TRANSACTION_PATH, "value of 'reason'");
        Optional<String> idempotencyKey =
                nodes.string(p.get("idempotencyKey"), TRANSACTION_PATH, "value of 'idempotencyKey'");
        List<Write> writes = writes(p.get("writes"));
        if (nodes.hasProblems()) {
            return invalid();
        }
        Transaction transaction = new Transaction(principal, onBehalfOf, reason, idempotencyKey, writes);
        return new PayloadReadResult.Read(
                new TransactionPayload(transaction, new PayloadLocations(rootLocation, entries)));
    }

    private PayloadReadResult invalid() {
        return new PayloadReadResult.Invalid(nodes.ordered());
    }

    /** The path of the element that the node at a position in the payload belongs to: a write, or the transaction. */
    private static String elementPath(List<String> tokens) {
        if (tokens.size() >= 2 && tokens.getFirst().equals("writes") && tokens.get(1).chars().allMatch(Character::isDigit)) {
            return PayloadKeys.WRITE_PATH + tokens.get(1);
        }
        return TRANSACTION_PATH;
    }

    // ------------------------------------------------------------------ writes

    private List<Write> writes(Entry entry) {
        if (entry == null) {
            return List.of();
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), TRANSACTION_PATH, "value of 'writes'", "a list of writes");
            return List.of();
        }
        List<Write> result = new ArrayList<>();
        for (int position = 0; position < sequence.getValue().size(); position++) {
            write(sequence.getValue().get(position), Pointers.child(entry.pointer(), position), position)
                    .ifPresent(result::add);
        }
        return result;
    }

    /** Reads one write: a mapping with exactly one key, which names its kind. */
    private Optional<Write> write(Node item, String pointer, int position) {
        String path = PayloadKeys.WRITE_PATH + position;
        if (!(item instanceof MappingNode mapping)) {
            nodes.invalidKind(item, pointer, path, "write at position " + position,
                    "a mapping with one key naming its kind");
            return Optional.empty();
        }
        DocumentLocation at = NodeReader.location(pointer, mapping);
        Map<String, Entry> kinds = new LinkedHashMap<>();
        boolean otherKeys = false;
        for (NodeTuple tuple : mapping.getValue()) {
            Optional<String> key = nodes.key(tuple.getKeyNode(), pointer, path);
            if (key.isEmpty()) {
                otherKeys = true;
            } else if (kinds.containsKey(key.get())) {
                nodes.duplicate(key.get(), tuple.getKeyNode(), Pointers.child(pointer, key.get()), path,
                        "the write at position " + position);
            } else if (!PayloadKeys.KINDS.contains(key.get())) {
                unknownKind(key.get(), path, position, at);
                otherKeys = true;
            } else {
                kinds.put(key.get(),
                        new Entry(key.get(), tuple.getKeyNode(), tuple.getValueNode(), Pointers.child(pointer, key.get())));
            }
        }
        if (kinds.isEmpty()) {
            if (!otherKeys) {
                nodes.add(ProblemCode.MISSING_KEY, path, "The write at position " + position + " has no key naming "
                        + "its kind.", "one of " + String.join(", ", PayloadKeys.KINDS), null,
                        "Add one of the keys " + String.join(", ", PayloadKeys.KINDS) + ".", at);
            }
            return Optional.empty();
        }
        Entry kind = kinds.values().iterator().next();
        if (kinds.size() > 1) {
            String found = String.join(", ", kinds.keySet());
            nodes.add(ProblemCode.INVALID_VALUE, path, "A write has one key naming its kind, but the write at position "
                    + position + " has " + found + ".", "exactly one of " + String.join(", ", PayloadKeys.KINDS), found,
                    "Write one write for each kind.", at);
        }
        PayloadLocations.Kind located = switch (kind.key()) {
            case "create" -> PayloadLocations.Kind.CREATE;
            case "relate" -> PayloadLocations.Kind.RELATE;
            case "patch" -> PayloadLocations.Kind.PATCH;
            case "replace" -> PayloadLocations.Kind.REPLACE;
            default -> PayloadLocations.Kind.END_DATE;
        };
        Located here = new Located(located, at);
        Optional<Write> write = switch (located) {
            case CREATE -> create(kind, path, position, here);
            case RELATE -> relate(kind, path, position, here);
            case PATCH -> patch(kind, path, position, here);
            case REPLACE -> replace(kind, path, position, here);
            case END_DATE -> endDate(kind, path, position, here);
        };
        write.ifPresent(ignored -> entries.add(here.entry()));
        return write;
    }

    /** A key that names no kind of write is located at the write, and its hint lists the kinds, the closest first. */
    private void unknownKind(String key, String path, int position, DocumentLocation at) {
        String closest = ClosestKey.of(key, PayloadKeys.KINDS).map(name -> "Did you mean '" + name + "'? ").orElse("");
        nodes.add(ProblemCode.UNKNOWN_KEY, path, "The key '" + key + "' does not name a kind of write, in the write at "
                + "position " + position + ".", "one of " + String.join(", ", PayloadKeys.KINDS), key,
                closest + "Use one of " + String.join(", ", ClosestKey.ranked(key, PayloadKeys.KINDS)) + ".", at);
    }

    private Optional<Write> create(Entry kind, String path, int position, Located here) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the create at position " + position, PayloadKeys.CREATE, PayloadKeys.CREATE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<TemporaryId> temporaryId = temporaryId(p.get("temporaryId"), path);
        Optional<EntityId> id = nodes.string(p.get("id"), path, "value of 'id'").map(EntityId::new);
        String entityType = nodes.string(p.get("entityType"), path, "value of 'entityType'").orElse("");
        EffectivePeriod period = period(p.get("from"), p.get("until"), path);
        Attributes attributes = schema.entityType(entityType).<Attributes>map(type -> type::attribute).orElse(NO_ATTRIBUTES);
        Map<String, Value> values = values(p.get("values"), attributes, path, here);
        Subject subject = new Subject(false, entityType);
        temporaryId.ifPresent(temporary -> creates(temporary.toString(), subject, here));
        id.ifPresent(supplied -> creates(supplied.value(), subject, here));
        return Optional.of(new Write.Create(temporaryId, id, entityType, period, values));
    }

    private Optional<Write> relate(Entry kind, String path, int position, Located here) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the relate at position " + position, PayloadKeys.RELATE, PayloadKeys.RELATE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<TemporaryId> temporaryId = temporaryId(p.get("temporaryId"), path);
        Optional<RelationshipId> id = nodes.string(p.get("id"), path, "value of 'id'").map(RelationshipId::new);
        String relationshipType = nodes.string(p.get("relationshipType"), path, "value of 'relationshipType'").orElse("");
        Optional<Reference> source = reference(p.get("source"), path);
        Optional<Reference> target = reference(p.get("target"), path);
        EffectivePeriod period = period(p.get("from"), p.get("until"), path);
        Attributes attributes = schema.relationshipType(relationshipType).<Attributes>map(type -> type::attribute)
                .orElse(NO_ATTRIBUTES);
        Map<String, Value> values = values(p.get("values"), attributes, path, here);
        Subject subject = new Subject(true, relationshipType);
        temporaryId.ifPresent(temporary -> creates(temporary.toString(), subject, here));
        id.ifPresent(supplied -> creates(supplied.value(), subject, here));
        source.ifPresent(end -> here.mentions.add(end.text()));
        target.ifPresent(end -> here.mentions.add(end.text()));
        return Optional.of(new Write.Relate(temporaryId, id, relationshipType, source.map(Reference::ref).orElse(NO_REFERENCE),
                target.map(Reference::ref).orElse(NO_REFERENCE), period, values));
    }

    private Optional<Write> patch(Entry kind, String path, int position, Located here) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the patch at position " + position, PayloadKeys.PATCH, PayloadKeys.PATCH_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Reference> target = reference(p.get("target"), path);
        target.ifPresent(found -> here.own.add(found.text()));
        Attributes attributes = attributesOf(target);
        EffectivePeriod period = period(p.get("from"), p.get("until"), path);
        // The attributes to clear come first, so that a key which both clears and sets an attribute is located at the set.
        Set<String> clear = clear(p.get("clear"), attributes, path, here);
        Map<String, Value> set = values(p.get("set"), attributes, path, here);
        ConflictCheck check = conflictCheck(p.get("conflictCheck"), path);
        return Optional.of(new Write.Patch(target.map(Reference::ref).orElse(NO_REFERENCE), period, set, clear, check));
    }

    private Optional<Write> replace(Entry kind, String path, int position, Located here) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the replace at position " + position, PayloadKeys.REPLACE, PayloadKeys.REPLACE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Reference> target = reference(p.get("target"), path);
        target.ifPresent(found -> here.own.add(found.text()));
        EffectivePeriod period = period(p.get("from"), p.get("until"), path);
        Map<String, Value> values = values(p.get("values"), attributesOf(target), path, here);
        ConflictCheck check = conflictCheck(p.get("conflictCheck"), path);
        return Optional.of(new Write.Replace(target.map(Reference::ref).orElse(NO_REFERENCE), period, values, check));
    }

    private Optional<Write> endDate(Entry kind, String path, int position, Located here) {
        Optional<Map<String, Entry>> properties = nodes.properties(kind.value(), kind.pointer(), path,
                "the endDate at position " + position, PayloadKeys.END_DATE, PayloadKeys.END_DATE_REQUIRED);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Entry> p = properties.get();
        Optional<Reference> target = reference(p.get("target"), path);
        target.ifPresent(found -> here.own.add(found.text()));
        Instant from = instant(p.get("from"), path).orElse(Instant.EPOCH);
        ConflictCheck check = conflictCheck(p.get("conflictCheck"), path);
        return Optional.of(new Write.EndDate(target.map(Reference::ref).orElse(NO_REFERENCE), from, check));
    }

    /** Remembers what a create or relate makes, by the text that later writes refer to it with. */
    private void creates(String name, Subject subject, Located here) {
        here.own.add(name);
        created.putIfAbsent(name, subject);
    }

    // ------------------------------------------------------------------ references

    /**
     * Reads the temporary ID of a create or relate. It is written as a reference to it is, with a leading {@code $};
     * the name alone is accepted too.
     */
    private Optional<TemporaryId> temporaryId(Entry entry, String path) {
        Optional<String> text = nodes.string(entry, path, "value of 'temporaryId'");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String name = text.get().startsWith("$") ? text.get().substring(1) : text.get();
        if (name.isBlank()) {
            invalidReference(entry, path, "a temporary ID needs a name");
            return Optional.empty();
        }
        return Optional.of(new TemporaryId(name));
    }

    /**
     * Reads a reference: {@code $} and a temporary ID, or an ID. An ID is an entity ID or a relationship ID as the
     * earlier writes of the payload or the lookup say, and an entity ID if they do not know it.
     */
    private Optional<Reference> reference(Entry entry, String path) {
        Optional<String> text = nodes.string(entry, path, "value of '" + (entry == null ? "" : entry.key()) + "'");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String written = text.get();
        if (written.startsWith("$")) {
            if (written.substring(1).isBlank()) {
                invalidReference(entry, path, "a temporary ID needs a name after the $");
                return Optional.empty();
            }
            return Optional.of(new Reference(new TemporaryId(written.substring(1)), written,
                    Optional.ofNullable(created.get(written))));
        }
        if (written.isBlank()) {
            invalidReference(entry, path, "an ID is not blank");
            return Optional.empty();
        }
        Optional<Subject> subject = Optional.ofNullable(created.get(written)).or(() -> lookup.find(written)
                .map(known -> new Subject(known.kind() == IdLookup.Kind.RELATIONSHIP, known.typeName())));
        Ref ref = subject.filter(Subject::relationship).isPresent() ? new RelationshipId(written) : new EntityId(written);
        return Optional.of(new Reference(ref, written, subject));
    }

    private void invalidReference(Entry entry, String path, String reason) {
        String found = NodeReader.describe(entry.value());
        nodes.add(ProblemCode.INVALID_VALUE, path, "The value of '" + entry.key() + "' is not a reference: " + reason
                + ".", "a reference: $ and a temporary ID, or an ID", found, "Write a temporary ID as \"$name\", for "
                + "example \"$acme\", and an ID as it is.", NodeReader.location(entry.pointer(), entry.value()));
    }

    /** The attributes of the type of a patched or replaced target, if the payload or the lookup knows what it is. */
    private Attributes attributesOf(Optional<Reference> target) {
        if (target.isEmpty() || target.get().subject().isEmpty()) {
            return NO_ATTRIBUTES;
        }
        Subject subject = target.get().subject().get();
        return subject.relationship()
                ? schema.relationshipType(subject.typeName()).<Attributes>map(type -> type::attribute).orElse(NO_ATTRIBUTES)
                : schema.entityType(subject.typeName()).<Attributes>map(type -> type::attribute).orElse(NO_ATTRIBUTES);
    }

    // ------------------------------------------------------------------ values

    /**
     * Reads the values of a write: a mapping from attribute names to literals. Each literal has the value type of its
     * attribute if it fits; the names are kept as written, and located by what they are written as and by the
     * attribute's name in the schema, which is how the engine calls the attribute in its problems.
     */
    private Map<String, Value> values(Entry entry, Attributes attributes, String path, Located here) {
        Map<String, Value> result = new LinkedHashMap<>();
        if (entry == null) {
            return result;
        }
        for (Entry member : nodes.named(entry.value(), entry.pointer(), path, "'" + entry.key() + "'",
                "a mapping from attribute names to values", key -> path + "." + key)) {
            Optional<Attribute> attribute = attributes.find(member.key());
            DocumentLocation at = NodeReader.location(member.pointer(), member.keyNode());
            locate(member.key(), attribute, at, here);
            nodes.literal(member.value(), member.pointer(), attribute.map(Attribute::valueType).orElse(null),
                    path + "." + member.key(), "value of '" + member.key() + "'")
                    .ifPresent(value -> result.put(member.key(), value));
        }
        return result;
    }

    /** Reads the attributes to clear: a list of names. */
    private Set<String> clear(Entry entry, Attributes attributes, String path, Located here) {
        Set<String> result = new LinkedHashSet<>();
        if (entry == null) {
            return result;
        }
        if (!(entry.value() instanceof SequenceNode sequence)) {
            nodes.invalidKind(entry.value(), entry.pointer(), path, "value of 'clear'", "a list of attribute names");
            return result;
        }
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node item = sequence.getValue().get(i);
            String pointer = Pointers.child(entry.pointer(), i);
            if (item instanceof ScalarNode scalar && Scalars.isString(scalar)) {
                result.add(scalar.getValue());
                locate(scalar.getValue(), attributes.find(scalar.getValue()), NodeReader.location(pointer, item), here);
            } else {
                nodes.invalidKind(item, pointer, path, "item " + (i + 1) + " of 'clear'", "a string");
            }
        }
        return result;
    }

    /** Remembers where an attribute is written, under the name as written and the name in the schema. */
    private static void locate(String written, Optional<Attribute> attribute, DocumentLocation at, Located here) {
        here.attributes.put(DocumentLocations.key(written), at);
        attribute.ifPresent(found -> here.attributes.put(DocumentLocations.key(found.name()), at));
    }

    // ------------------------------------------------------------------ instants and conflict checks

    private EffectivePeriod period(Entry from, Entry until, String path) {
        return new EffectivePeriod(instant(from, path).orElse(Instant.EPOCH), instant(until, path));
    }

    /** Reads an instant: a string in ISO 8601 form, in UTC. An absent value is empty. */
    private Optional<Instant> instant(Entry entry, String path) {
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.value() instanceof ScalarNode scalar) {
            Optional<Instant> instant = Scalars.instantValue(scalar);
            if (instant.isPresent()) {
                return instant;
            }
        }
        String expected = "an instant in ISO 8601 UTC form, such as " + INSTANT_EXAMPLE;
        String found = NodeReader.describe(entry.value());
        nodes.add(ProblemCode.INVALID_VALUE, path, "The value of '" + entry.key() + "' must be " + expected + ", but it is "
                + found + ".", expected, found, dateHint(entry.value()), NodeReader.location(entry.pointer(), entry.value()));
        return Optional.empty();
    }

    /** A date written where an instant is needed gets a hint that completes it. */
    private static String dateHint(Node node) {
        if (node instanceof ScalarNode scalar && Scalars.isString(scalar)) {
            try {
                LocalDate.parse(scalar.getValue());
                return "Add the time of day and the zone: \"" + scalar.getValue() + "T00:00:00Z\".";
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    /** Reads a conflict check: a mapping with the one key {@code perEntity} or {@code perAttribute}, whose value is a basis. */
    private ConflictCheck conflictCheck(Entry entry, String path) {
        if (entry == null) {
            return ConflictCheck.none();
        }
        Optional<Map<String, Entry>> properties = nodes.properties(entry.value(), entry.pointer(), path,
                "the conflict check", PayloadKeys.CONFLICT_CHECK, List.of());
        if (properties.isEmpty()) {
            return ConflictCheck.none();
        }
        Map<String, Entry> p = properties.get();
        DocumentLocation at = NodeReader.location(entry.pointer(), entry.value());
        String choices = String.join(" or ", PayloadKeys.CONFLICT_CHECK);
        if (p.isEmpty()) {
            if (entry.value() instanceof MappingNode mapping && mapping.getValue().isEmpty()) {
                nodes.add(ProblemCode.MISSING_KEY, path, "The conflict check has no key.", "the key " + choices, null,
                        "Add the key perEntity or perAttribute, with the basis the entity was read at.", at);
            }
            return ConflictCheck.none();
        }
        if (p.size() > 1) {
            nodes.add(ProblemCode.INVALID_VALUE, path, "A conflict check has one key, " + choices + ", but this one has "
                    + "both.", "exactly one of " + String.join(", ", PayloadKeys.CONFLICT_CHECK), "both",
                    "Keep the check you mean, and remove the other.", at);
        }
        Entry check = p.values().iterator().next();
        Optional<Basis> basis = basis(check, path);
        if (basis.isEmpty()) {
            return ConflictCheck.none();
        }
        return check.key().equals("perEntity")
                ? ConflictCheck.perEntity(basis.get()) : ConflictCheck.perAttribute(basis.get());
    }

    /** Reads a basis: the number of a committed transaction, a whole number from 0. */
    private Optional<Basis> basis(Entry entry, String path) {
        if (entry.value() instanceof ScalarNode scalar && Scalars.isJsonInteger(scalar)) {
            BigInteger value = Scalars.integerValue(scalar);
            if (value.signum() >= 0 && value.bitLength() < Long.SIZE) {
                return Optional.of(new Basis(value.longValueExact()));
            }
        }
        nodes.invalidKind(entry.value(), entry.pointer(), path, "value of '" + entry.key() + "'",
                "a basis: the number of a committed transaction, a whole number from 0");
        return Optional.empty();
    }
}
