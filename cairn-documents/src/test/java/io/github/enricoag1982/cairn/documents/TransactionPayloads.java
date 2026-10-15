package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.Ref;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.documents.WrittenNode.Mapping;
import io.github.enricoag1982.cairn.documents.WrittenNode.Plain;
import io.github.enricoag1982.cairn.documents.WrittenNode.Sequence;
import io.github.enricoag1982.cairn.documents.WrittenNode.Text;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders a transaction as a JSON payload, the inverse of reading one, and says what reading that payload gives back.
 * The tests use it to check the payloads of the stories of the reference domain.
 */
final class TransactionPayloads {

    private TransactionPayloads() {
    }

    // ------------------------------------------------------------------ rendering

    /** Renders a transaction as a JSON payload of format version 1. */
    static String render(Transaction transaction) {
        Mapping root = new Mapping().put("cairn", new Plain("1")).put("principal", transaction.principal());
        transaction.onBehalfOf().ifPresent(party -> root.put("onBehalfOf", party));
        transaction.reason().ifPresent(reason -> root.put("reason", reason));
        transaction.idempotencyKey().ifPresent(key -> root.put("idempotencyKey", key));
        root.put("writes", new Sequence(transaction.writes().stream().<WrittenNode>map(TransactionPayloads::write).toList()));
        return JsonText.render(root);
    }

    private static Mapping write(Write write) {
        return switch (write) {
            case Write.Create create -> {
                Mapping body = new Mapping();
                create.temporaryId().ifPresent(temporaryId -> body.put("temporaryId", temporaryId.toString()));
                create.id().ifPresent(id -> body.put("id", id.value()));
                body.put("entityType", create.entityType());
                period(body, create.period());
                values(body, "values", create.values(), false);
                yield new Mapping().put("create", body);
            }
            case Write.Relate relate -> {
                Mapping body = new Mapping();
                relate.temporaryId().ifPresent(temporaryId -> body.put("temporaryId", temporaryId.toString()));
                relate.id().ifPresent(id -> body.put("id", id.value()));
                body.put("relationshipType", relate.relationshipType())
                        .put("source", reference(relate.source()))
                        .put("target", reference(relate.target()));
                period(body, relate.period());
                values(body, "values", relate.values(), false);
                yield new Mapping().put("relate", body);
            }
            case Write.Patch patch -> {
                Mapping body = new Mapping().put("target", reference(patch.target()));
                period(body, patch.period());
                values(body, "set", patch.set(), false);
                if (!patch.clear().isEmpty()) {
                    body.put("clear", new Sequence(patch.clear().stream().<WrittenNode>map(Text::new).toList()));
                }
                conflictCheck(body, patch.check());
                yield new Mapping().put("patch", body);
            }
            case Write.Replace replace -> {
                Mapping body = new Mapping().put("target", reference(replace.target()));
                period(body, replace.period());
                values(body, "values", replace.values(), true);
                conflictCheck(body, replace.check());
                yield new Mapping().put("replace", body);
            }
            case Write.EndDate endDate -> {
                Mapping body = new Mapping().put("target", reference(endDate.target()))
                        .put("from", endDate.from().toString());
                conflictCheck(body, endDate.check());
                yield new Mapping().put("endDate", body);
            }
        };
    }

    /** A reference as a payload writes it: a temporary ID with its {@code $}, an ID as it is. */
    private static String reference(Ref ref) {
        return ref.toString();
    }

    private static void period(Mapping body, EffectivePeriod period) {
        body.put("from", period.from().toString());
        period.until().ifPresent(until -> body.put("until", until.toString()));
    }

    private static void values(Mapping body, String key, Map<String, Value> values, boolean always) {
        if (always || !values.isEmpty()) {
            Mapping mapping = new Mapping();
            values.forEach((name, value) -> mapping.put(name, DocumentWriter.literal(value)));
            body.put(key, mapping);
        }
    }

    private static void conflictCheck(Mapping body, ConflictCheck check) {
        switch (check) {
            case ConflictCheck.None _ -> { }
            case ConflictCheck.PerEntity perEntity -> body.put("conflictCheck",
                    new Mapping().put("perEntity", new Plain(Long.toString(perEntity.readAt().value()))));
            case ConflictCheck.PerAttribute perAttribute -> body.put("conflictCheck",
                    new Mapping().put("perAttribute", new Plain(Long.toString(perAttribute.readAt().value()))));
        }
    }

    // ------------------------------------------------------------------ what reading gives back

    /** What a reference names, found from the payload or from the lookup: whether it is a relationship, and its type. */
    private record Subject(boolean relationship, String typeName) {
    }

    /**
     * The transaction that reading its payload gives back: the same writes, with the values of each write in the value
     * type of their attribute where the type of the write is known, as the reader types them. A whole number of a decimal
     * attribute comes back as a decimal, and a value of an unknown attribute or of an unknown type keeps its type.
     */
    static Transaction readBack(Transaction transaction, Schema schema, IdLookup lookup) {
        Map<String, Subject> created = new HashMap<>();
        List<Write> writes = new ArrayList<>();
        for (Write write : transaction.writes()) {
            writes.add(switch (write) {
                case Write.Create create -> {
                    Subject subject = new Subject(false, create.entityType());
                    create.temporaryId().ifPresent(temporaryId -> created.put(temporaryId.toString(), subject));
                    create.id().ifPresent(id -> created.put(id.value(), subject));
                    yield new Write.Create(create.temporaryId(), create.id(), create.entityType(), create.period(),
                            typed(create.values(), attributesOf(schema, subject)));
                }
                case Write.Relate relate -> {
                    Subject subject = new Subject(true, relate.relationshipType());
                    relate.temporaryId().ifPresent(temporaryId -> created.put(temporaryId.toString(), subject));
                    relate.id().ifPresent(id -> created.put(id.value(), subject));
                    yield new Write.Relate(relate.temporaryId(), relate.id(), relate.relationshipType(), relate.source(),
                            relate.target(), relate.period(), typed(relate.values(), attributesOf(schema, subject)));
                }
                case Write.Patch patch -> {
                    Function<String, Optional<Attribute>> attributes = attributesOf(schema, subjectOf(patch.target(), created, lookup));
                    yield new Write.Patch(patch.target(), patch.period(), typed(patch.set(), attributes), patch.clear(),
                            patch.check());
                }
                case Write.Replace replace -> {
                    Function<String, Optional<Attribute>> attributes = attributesOf(schema, subjectOf(replace.target(), created, lookup));
                    yield new Write.Replace(replace.target(), replace.period(), typed(replace.values(), attributes),
                            replace.check());
                }
                case Write.EndDate endDate -> endDate;
            });
        }
        return new Transaction(transaction.principal(), transaction.onBehalfOf(), transaction.reason(),
                transaction.idempotencyKey(), writes);
    }

    private static Optional<Subject> subjectOf(Ref ref, Map<String, Subject> created, IdLookup lookup) {
        String text = ref.toString();
        return Optional.ofNullable(created.get(text)).or(() -> lookup.find(text)
                .map(known -> new Subject(known.kind() == IdLookup.Kind.RELATIONSHIP, known.typeName())));
    }

    private static Function<String, Optional<Attribute>> attributesOf(Schema schema, Subject subject) {
        return attributesOf(schema, Optional.of(subject));
    }

    private static Function<String, Optional<Attribute>> attributesOf(Schema schema, Optional<Subject> subject) {
        if (subject.isEmpty()) {
            return name -> Optional.empty();
        }
        return subject.get().relationship()
                ? name -> schema.relationshipType(subject.get().typeName()).flatMap(type -> type.attribute(name))
                : name -> schema.entityType(subject.get().typeName()).flatMap(type -> type.attribute(name));
    }

    private static Map<String, Value> typed(Map<String, Value> values, Function<String, Optional<Attribute>> attributes) {
        Map<String, Value> result = new LinkedHashMap<>();
        values.forEach((name, value) -> result.put(name,
                attributes.apply(name).map(attribute -> widened(value, attribute.valueType())).orElse(value)));
        return result;
    }

    /** A whole number read for a long or decimal attribute has that attribute's type. */
    private static Value widened(Value value, ValueType type) {
        return switch (type) {
            case LONG -> value instanceof Value.IntValue integer ? Value.of((long) integer.value()) : value;
            case DECIMAL -> switch (value) {
                case Value.IntValue integer -> Value.of(BigDecimal.valueOf(integer.value()));
                case Value.LongValue number -> Value.of(BigDecimal.valueOf(number.value()));
                default -> value;
            };
            default -> value;
        };
    }
}
