package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

/**
 * A small, deterministic generator of schema definitions for property-style tests: the same seed always gives the same
 * origin and definition.
 *
 * <p>The definitions have the shape of valid ones and use every property: descriptions, examples and range bounds of the
 * attribute's own value type, allowed values, patterns, lengths, rules, aliases, deprecation, abstract types,
 * supertypes, traits, compositions, symmetric types, inverse aliases and all four multiplicities. They are not
 * registered, so they need not be valid in every respect. Strings are chosen to be awkward for YAML and JSON: words that
 * read as numbers, booleans or null, syntax characters, escapes, control characters and non-ASCII text.
 *
 * <p>Some examples and bounds of long and decimal attributes are numbers of a narrower type, an int on a long or a
 * decimal attribute and a long on a decimal one. A document does not tell them apart from numbers of the attribute's
 * type, so reading the written document gives them back in the attribute's type ({@link Generated#readBack()}).
 */
final class GeneratedDefinitions {

    /**
     * One generated document.
     *
     * @param origin     the origin
     * @param definition the schema definition, as it is written: a number may be of a narrower type than its attribute
     */
    record Generated(Origin origin, SchemaDefinition definition) {

        /**
         * The definition as reading the written document gives it back: every number of a narrower type than its
         * attribute's is in the attribute's type, and everything else is as generated.
         *
         * @return the definition with widened numbers
         */
        SchemaDefinition readBack() {
            return widened(definition);
        }
    }

    /** The share of examples and bounds of long and decimal attributes that are numbers of a narrower type. */
    private static final double NARROWER = 0.3;

    private static final List<String> AWKWARD_STRINGS = List.of(
            "", " ", " leading", "trailing ", "null", "Null", "~", "true", "False", "yes", "NO", "123", "-1", "+5", "1.5",
            "1e3", "0x1F", "0o17", ".inf", "-.INF", ".nan", "12:30", "a: b", "a:b", "a:", ":a", "- a", "-", "?", "? a",
            "# comment", "a #b", "a#b", "[a, b]", "{a: b}", "a, b", "*alias", "&anchor", "!tag", "!!str x", "|", ">",
            "%directive", "@at", "`tick", "'single'", "\"double\"", "back\\slash", "tab\there", "new\nline", "cr\rlf",
            "\u0000nul", "\u007f", "\u0085", " ", " ", "﻿", "bell\u0007", "é", "日本語",
            "emoji 😀", "---", "...", "<<", "= ", "0..*", "1..1", "[A-Z0-9]{18}[0-9]{2}", "\\d{3}", "a\\",
            "Corporate", "Retail", "Legal Entity Identifier (ISO 17442), as issued", "It's a \"quoted\" word; really?");

    private static final List<String> PATTERNS = List.of("[A-Z]{3}", "[A-Z0-9]{18}[0-9]{2}", "[a-z]+(-[a-z]+)*", "\\d{3}", "");

    private static final List<String> SPECIAL_NAMES = List.of(
            "null", "Null", "true", "False", "yes", "NO", "inf", "nan", "y", "n", "on", "off", "e1", "x1");

    private static final List<String> VERSIONS = List.of(
            "1.0.0", "2", "1.10", "0", "true", "null", "v1", "2026-01-05", "", " 1 ", "1e3", "0x1F", "1.0.0-rc.1+build.5");

    private static final List<String> WORDS = List.of(
            "party", "account", "name", "the", "owner", "of", "a", "legal", "entity", "balance", "share", "role", "identifier",
            "customer", "address", "holds", "total", "kept", "per", "unit");

    private final Random random;
    private final Set<String> typeNames = new HashSet<>();

    private GeneratedDefinitions(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generates an origin and a schema definition.
     *
     * @param seed the seed; the same seed gives the same result
     * @return the origin and the definition
     */
    static Generated generate(long seed) {
        return new GeneratedDefinitions(seed).origin();
    }

    private Generated origin() {
        Origin origin = new Origin(chance(0.1) ? pick(AWKWARD_STRINGS) : identifier(), pick(VERSIONS));
        List<String> traitNames = names(between(0, 3));
        List<TraitDefinition> traits = new ArrayList<>();
        for (String name : traitNames) {
            traits.add(new TraitDefinition(name, attributes(), aliases(), chance(0.2), description()));
        }
        List<String> entityNames = names(between(0, 6));
        List<EntityTypeDefinition> entityTypes = new ArrayList<>();
        for (int i = 0; i < entityNames.size(); i++) {
            entityTypes.add(entityType(entityNames.get(i), entityNames.subList(0, i), traitNames));
        }
        List<String> relationshipNames = names(between(0, 5));
        List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();
        for (int i = 0; i < relationshipNames.size(); i++) {
            relationshipTypes.add(relationshipType(relationshipNames.get(i), relationshipNames.subList(0, i), entityNames));
        }
        return new Generated(origin, new SchemaDefinition(entityTypes, traits, relationshipTypes));
    }

    // ------------------------------------------------------------------ types

    private EntityTypeDefinition entityType(String name, List<String> earlier, List<String> traitNames) {
        return new EntityTypeDefinition(
                name,
                chance(0.3),
                chance(0.4) && !earlier.isEmpty() ? Optional.of(pick(earlier)) : Optional.empty(),
                subset(traitNames, 2),
                attributes(),
                aliases(),
                chance(0.2),
                description(),
                rules());
    }

    private RelationshipTypeDefinition relationshipType(String name, List<String> earlier, List<String> entityNames) {
        return new RelationshipTypeDefinition(
                name,
                entityNames.isEmpty() ? identifier() : pick(entityNames),
                entityNames.isEmpty() ? identifier() : pick(entityNames),
                multiplicity(),
                multiplicity(),
                uniqueName(),
                aliases(),
                chance(0.25) ? RelationshipKind.COMPOSITION : RelationshipKind.ASSOCIATION,
                chance(0.2),
                chance(0.3) && !earlier.isEmpty() ? Optional.of(pick(earlier)) : Optional.empty(),
                attributes(),
                aliases(),
                chance(0.2),
                description(),
                rules());
    }

    private Multiplicity multiplicity() {
        return pick(List.of(Multiplicity.ZERO_OR_ONE, Multiplicity.EXACTLY_ONE, Multiplicity.ZERO_OR_MORE,
                Multiplicity.ONE_OR_MORE));
    }

    private List<RuleReference> rules() {
        List<RuleReference> rules = new ArrayList<>();
        for (int i = between(0, 2); i > 0; i--) {
            rules.add(new RuleReference(pick(List.of("lei-checksum", "total-ownership-at-most-100", "a-rule", "x1")),
                    between(1, 5)));
        }
        return rules;
    }

    // ------------------------------------------------------------------ attributes

    private List<AttributeDefinition> attributes() {
        Set<String> used = new HashSet<>();
        List<AttributeDefinition> attributes = new ArrayList<>();
        for (int i = between(0, 4); i > 0; i--) {
            String name = identifier();
            if (used.add(name.toLowerCase(Locale.ROOT))) {
                attributes.add(attribute(name));
            }
        }
        return attributes;
    }

    private AttributeDefinition attribute(String name) {
        ValueType type = pick(List.of(ValueType.values()));
        boolean string = type == ValueType.STRING;
        List<Literal> examples = new ArrayList<>();
        for (int i = between(0, 3); i > 0; i--) {
            examples.add(written(type));
        }
        List<String> allowedValues = new ArrayList<>();
        if (string && chance(0.3)) {
            for (int i = between(1, 4); i > 0; i--) {
                allowedValues.add(text());
            }
        }
        return new AttributeDefinition(
                name,
                type,
                chance(0.4),
                chance(0.2),
                type.isOrdered() && chance(0.5) ? Optional.of(range(type)) : Optional.empty(),
                aliases(),
                chance(0.2),
                description(),
                examples,
                allowedValues,
                string && chance(0.3) ? Optional.of(pick(PATTERNS)) : Optional.empty(),
                string && chance(0.3) ? OptionalInt.of(between(0, 50)) : OptionalInt.empty(),
                string && chance(0.3) ? OptionalInt.of(between(0, 500)) : OptionalInt.empty());
    }

    /** A range whose bounds have the attribute's value type; each bound is optional, so a range may have none. */
    private Range range(ValueType type) {
        Optional<Bound> lower = chance(0.6) ? Optional.of(new Bound(written(type), chance(0.5))) : Optional.empty();
        Optional<Bound> upper = chance(0.6) ? Optional.of(new Bound(written(type), chance(0.5))) : Optional.empty();
        return new Range(lower, upper);
    }

    /**
     * A literal as it is written for an attribute: of the attribute's value type, or, for a long or a decimal attribute,
     * now and then a number of a narrower type.
     */
    private Literal written(ValueType type) {
        if (type == ValueType.LONG && chance(NARROWER)) {
            return Literal.of(intValue());
        }
        if (type == ValueType.DECIMAL && chance(NARROWER)) {
            return chance(0.5) ? Literal.of(intValue()) : Literal.of(longValue());
        }
        return literal(type);
    }

    private Literal literal(ValueType type) {
        return switch (type) {
            case STRING -> Literal.of(text());
            case BOOLEAN -> Literal.of(random.nextBoolean());
            case INT -> Literal.of(intValue());
            case LONG -> Literal.of(longValue());
            case DECIMAL -> Literal.of(decimal());
            case DATE -> Literal.of(LocalDate.ofEpochDay(between(-1_000_000, 3_000_000)));
            case INSTANT -> Literal.of(instant());
        };
    }

    private int intValue() {
        return pick(List.of(0, 1, -1, 100, Integer.MIN_VALUE, Integer.MAX_VALUE, random.nextInt()));
    }

    private long longValue() {
        return pick(List.of(0L, -1L, 3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, random.nextLong(),
                (long) random.nextInt()));
    }

    /** A decimal with a scale from -3 to 10: a negative scale is a value like 1E+3, a scale of 2 keeps 1.50 apart from 1.5. */
    private BigDecimal decimal() {
        if (chance(0.1)) {
            return pick(List.of(BigDecimal.ZERO, new BigDecimal("0.00"), BigDecimal.ONE, new BigDecimal("100"),
                    new BigDecimal("-0.5")));
        }
        return new BigDecimal(new BigInteger(between(1, 90), random), between(-3, 10));
    }

    private Instant instant() {
        long seconds = (long) between(-1_000_000_000, 1_000_000_000) * 100L;
        int nanos = pick(List.of(0, 123_000_000, 123_456_000, 123_456_789, random.nextInt(1_000_000_000)));
        return Instant.ofEpochSecond(seconds, nanos);
    }

    // ------------------------------------------------------------------ names and text

    private List<String> aliases() {
        List<String> aliases = new ArrayList<>();
        for (int i = between(0, 2); i > 0; i--) {
            aliases.add(chance(0.3) ? text() : identifier());
        }
        return aliases;
    }

    private Optional<String> description() {
        return chance(0.5) ? Optional.of(text()) : Optional.empty();
    }

    /** A string that is awkward for YAML or JSON, or a few words. */
    private String text() {
        if (chance(0.5)) {
            return pick(AWKWARD_STRINGS);
        }
        List<String> words = new ArrayList<>();
        for (int i = between(1, 6); i > 0; i--) {
            words.add(pick(WORDS));
        }
        return String.join(" ", words);
    }

    private List<String> names(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(uniqueName());
        }
        return names;
    }

    /** A name for a type or an inverse direction, different from every one given so far. */
    private String uniqueName() {
        while (true) {
            String name = chance(0.15) ? pick(SPECIAL_NAMES) : identifier();
            if (typeNames.add(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
    }

    /** A name in the form the engine accepts: a letter, then letters, digits and underscores. */
    private String identifier() {
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String more = letters + "0123456789_";
        StringBuilder name = new StringBuilder().append(letters.charAt(random.nextInt(letters.length())));
        for (int i = between(0, 8); i > 0; i--) {
            name.append(more.charAt(random.nextInt(more.length())));
        }
        return name.toString();
    }

    // ------------------------------------------------------------------ choices

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** A number from {@code low} to {@code high}, both included. */
    private int between(int low, int high) {
        return low + random.nextInt(high - low + 1);
    }

    private <T> T pick(List<T> choices) {
        return choices.get(random.nextInt(choices.size()));
    }

    private List<String> subset(List<String> names, int most) {
        List<String> chosen = new ArrayList<>();
        for (int i = between(0, most); i > 0 && !names.isEmpty(); i--) {
            String name = pick(names);
            if (!chosen.contains(name)) {
                chosen.add(name);
            }
        }
        return chosen;
    }

    // ------------------------------------------------------------------ what reading gives back

    /** The definition with every number in the value type of its attribute; everything else is as it is. */
    static SchemaDefinition widened(SchemaDefinition definition) {
        return new SchemaDefinition(
                definition.entityTypes().stream().map(type -> new EntityTypeDefinition(type.name(), type.isAbstract(),
                        type.supertype(), type.traits(), widened(type.attributes()), type.aliases(), type.deprecated(),
                        type.description(), type.rules())).toList(),
                definition.traits().stream().map(trait -> new TraitDefinition(trait.name(), widened(trait.attributes()),
                        trait.aliases(), trait.deprecated(), trait.description())).toList(),
                definition.relationshipTypes().stream().map(type -> new RelationshipTypeDefinition(type.name(),
                        type.source(), type.target(), type.targetsPerSource(), type.sourcesPerTarget(), type.inverseName(),
                        type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(), widened(type.attributes()),
                        type.aliases(), type.deprecated(), type.description(), type.rules())).toList());
    }

    private static List<AttributeDefinition> widened(List<AttributeDefinition> attributes) {
        return attributes.stream().map(GeneratedDefinitions::widened).toList();
    }

    private static AttributeDefinition widened(AttributeDefinition attribute) {
        ValueType type = attribute.valueType();
        return new AttributeDefinition(attribute.name(), type, attribute.required(), attribute.unique(),
                attribute.range().map(range -> new Range(
                        range.lower().map(bound -> new Bound(widened(bound.value(), type), bound.inclusive())),
                        range.upper().map(bound -> new Bound(widened(bound.value(), type), bound.inclusive())))),
                attribute.aliases(), attribute.deprecated(), attribute.description(),
                attribute.examples().stream().map(example -> widened(example, type)).toList(), attribute.allowedValues(),
                attribute.pattern(), attribute.minLength(), attribute.maxLength());
    }

    /** A number in the value type of its attribute: an int becomes a long or a decimal, and a long a decimal. */
    private static Literal widened(Literal literal, ValueType type) {
        return switch (literal) {
            case Literal.IntLiteral integer when type == ValueType.LONG -> Literal.of((long) integer.value());
            case Literal.IntLiteral integer when type == ValueType.DECIMAL ->
                    Literal.of(BigDecimal.valueOf(integer.value()));
            case Literal.LongLiteral number when type == ValueType.DECIMAL -> Literal.of(BigDecimal.valueOf(number.value()));
            default -> literal;
        };
    }
}
