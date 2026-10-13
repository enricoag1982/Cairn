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
import io.github.enricoag1982.cairn.schema.SchemaDefinition;

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
                .entityType("Organization", e -> e.supertype("Party").attribute("lei", STRING, a -> a.unique()))
                .entityType("Address", e -> e
                        .attribute("street", STRING, a -> a.required())
                        .attribute("city", STRING, a -> a.required())
                        .attribute("country", STRING, a -> a.required()))
                .entityType("Account", e -> e
                        .attribute("number", STRING, a -> a.required().unique())
                        .attribute("currency", STRING, a -> a.required())
                        .attribute("balance", DECIMAL, a -> a.required()))
                .entityType("CustomerRole", e -> e.attribute("segment", STRING, a -> a.required()))
                .relationshipType("Owns", r -> r
                        .from("Organization").to("Party")
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE)
                        .inverse("ownedBy")
                        .attribute("share", DECIMAL, a -> a.required().range(
                                Range.between(Bound.exclusive(Literal.of(0)), Bound.inclusive(Literal.of(100))))))
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
}
