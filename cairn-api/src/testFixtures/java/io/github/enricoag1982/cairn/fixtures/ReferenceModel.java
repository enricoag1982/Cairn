package io.github.enricoag1982.cairn.fixtures;

import static io.github.enricoag1982.cairn.schema.Multiplicity.EXACTLY_ONE;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;

import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.util.List;

/**
 * The reference domain of {@code docs/reference/reference-domain.md} as a schema definition.
 * {@code ReferenceModelTest} checks that the two stay in step.
 *
 * <p>The bounds of the range of {@code Owns.share} are decimal values, like the attribute: a schema document gives a
 * bound the value type of its attribute, and decimal values are equal only if their scales are.
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
                        .attribute("share", DECIMAL, a -> a.required().range(Range.between(
                                Bound.exclusive(Value.of(new BigDecimal("0"))),
                                Bound.inclusive(Value.of(new BigDecimal("100"))))))
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
     * <p>A store also needs the code of the rules it should run: {@link #executableRules()} and
     * {@link #declaredRules()} split these descriptors into the rules that have code and those that do not.
     *
     * @return the rules {@code lei-checksum} and {@code total-ownership-at-most-100}, both in version 1
     */
    public static List<RuleDescriptor> rules() {
        return List.of(LeiChecksumRule.DESCRIPTOR, TotalOwnershipRule.DESCRIPTOR);
    }

    /**
     * The rules of the reference domain that have code: {@code lei-checksum}, as {@link LeiChecksumRule}, and
     * {@code total-ownership-at-most-100}, as {@link TotalOwnershipRule}. Give them to a store, so that the store can
     * register the reference model and runs the rules at commit. Both rules of the reference domain have code, so
     * {@link #declaredRules()} is empty.
     *
     * @return new instances of the executable rules
     */
    public static List<Rule> executableRules() {
        return List.of(new LeiChecksumRule(), new TotalOwnershipRule());
    }

    /**
     * The descriptors of the rules of the reference domain that have no code. A store declares them, so that the
     * reference model can name them, and rejects writes that would need to run them. Every rule of the reference
     * domain has code, so there are none; tests that need a declared rule declare one of their own, such as
     * {@code name-not-blank}.
     *
     * @return the descriptors among {@link #rules()} that no {@linkplain #executableRules() executable rule} describes
     */
    public static List<RuleDescriptor> declaredRules() {
        List<RuleDescriptor> withCode = executableRules().stream().map(Rule::descriptor).toList();
        return rules().stream().filter(rule -> !withCode.contains(rule)).toList();
    }
}
