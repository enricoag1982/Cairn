package io.github.enricoag1982.cairn.fixtures;

import static io.github.enricoag1982.cairn.schema.Multiplicity.EXACTLY_ONE;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;

import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import java.util.List;

/**
 * The reference domain of {@code docs/reference/reference-domain.md} as a schema definition.
 * {@code ReferenceModelTest} checks that the two stay in step.
 */
public final class ReferenceModel {

    /** The origin name used for the reference model in scenarios. */
    public static final String ORIGIN_NAME = "reference-model";

    private ReferenceModel() {
    }

    /**
     * Creates the reference model's origin with a version.
     *
     * @param version the version, for example {@code 1.0.0}
     * @return the origin
     */
    public static Origin origin(String version) {
        return new Origin(ORIGIN_NAME, version);
    }

    /**
     * Builds the complete reference model.
     *
     * @return the schema definition
     */
    public static SchemaDefinition definition() {
        return SchemaDefinition.builder()
                .trait("Named", t -> t.attribute("name", STRING, a -> a.required()))
                .entityType("Party", e -> e.abstractType().includes("Named"))
                .entityType("Person", e -> e.supertype("Party").attribute("birthDate", DATE))
                .entityType("Organization", e -> e.supertype("Party")
                        .attribute("lei", STRING, a -> a.unique().pattern("[A-Z0-9]{18}[0-9]{2}"))
                        .rule("lei-checksum", 1))
                .entityType("Address", e -> e
                        .attribute("street", STRING, a -> a.required())
                        .attribute("city", STRING, a -> a.required())
                        .attribute("country", STRING, a -> a.required()))
                .entityType("Account", e -> e
                        .attribute("number", STRING, a -> a.required().unique())
                        .attribute("currency", STRING, a -> a.required().pattern("[A-Z]{3}"))
                        .attribute("balance", DECIMAL, a -> a.required()))
                .entityType("CustomerRole", e -> e.attribute("segment", STRING,
                        a -> a.required().allowedValues("Corporate", "Retail")))
                .relationshipType("Owns", r -> r
                        .from("Organization").to("Party")
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE)
                        .inverse("ownedBy")
                        .attribute("share", DECIMAL, a -> a.required().range(
                                Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100)))))
                        .rule("total-ownership-at-most-100", 1))
                .relationshipType("Employs", r -> r
                        .from("Organization").to("Person")
                        .inverse("employedBy")
                        .attribute("title", STRING))
                .relationshipType("HeldBy", r -> r
                        .from("Account").to("Party")
                        .targetsPerSource(EXACTLY_ONE).sourcesPerTarget(ZERO_OR_MORE)
                        .inverse("holds"))
                .relationshipType("HasAddress", r -> r
                        .from("Party").to("Address")
                        .composition()
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(EXACTLY_ONE)
                        .inverse("party"))
                .relationshipType("PlayedBy", r -> r
                        .from("CustomerRole").to("Party")
                        .targetsPerSource(EXACTLY_ONE).sourcesPerTarget(ZERO_OR_MORE)
                        .inverse("roles"))
                .build();
    }

    /**
     * Describes the two rules of the reference domain, which the reference model names. Create a registry with them
     * wherever the reference model is registered.
     *
     * @return the rules {@code lei-checksum} and {@code total-ownership-at-most-100}, both in version 1
     */
    public static List<RuleDescriptor> rules() {
        return List.of(
                new RuleDescriptor("lei-checksum", 1,
                        "The LEI's check digits are valid (ISO 17442, ISO 7064 MOD 97-10).",
                        "Organization",
                        List.of(new RuleCode("invalid-check-digits",
                                "The two check digits of the LEI do not match its other characters. "
                                        + "Correct the LEI."))),
                new RuleDescriptor("total-ownership-at-most-100", 1,
                        "The owners of a party hold at most 100% of it together, at every point in effective time.",
                        "Owns",
                        List.of(new RuleCode("over-100-percent",
                                "The shares of a party's owners add up to more than 100% at some point in effective "
                                        + "time. Lower a share, or end an ownership."))));
    }
}
