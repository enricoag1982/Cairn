package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.TypeId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides what a registration may change for a caller whose clearance limits it (ADR-0036, design D5 of
 * {@code add-attribute-access-control}): it compares the schema before the registration with the schema after it, both
 * compiled, and reports with {@code ACCESS_DENIED}, at the element concerned, every change that the clearance does not
 * allow:
 *
 * <ul>
 * <li>an attribute whose classification is added, changed or removed, where the classification before or after is one that
 * the clearance does not cover. Attributes are matched by their identifier, which a rename does not change, so a rename
 * that drops the classification is a change too. The path is the attribute, as {@code Account.balance}.
 * <li>a check that is added, or whose expression is changed, and reads an attribute whose classification the clearance
 * does not cover. The path is the check, as {@code Account.balance-above-1000}.
 * <li>a derived attribute that is added, or whose expression is changed, and reads such an attribute. The path is the
 * derived attribute.
 * </ul>
 *
 * <p>What a check or a derived attribute reads is what its compiled expression says it reads. A check or a derivation is
 * the same as before if the type that declares it, its name and its expression are, however many types it applies to
 * through inheritance. Without these limits a caller could declassify a value and then read it, or probe it with checks
 * and dry runs.
 */
final class AccessGuard {

    private AccessGuard() {
    }

    /**
     * The problems of a registration for a clearance.
     *
     * @param before    the schema before the registration
     * @param after     the schema the registration would produce
     * @param clearance the clearance of the caller
     * @return the problems, in the order of their paths; none if the clearance allows the registration
     */
    static List<Problem> problems(CompiledSchema before, CompiledSchema after, Clearance clearance) {
        Map<String, Problem> problems = new TreeMap<>();
        classifications(before, after, clearance, problems);
        checks(before, after, clearance, problems);
        derivations(before, after, clearance, problems);
        return new ArrayList<>(problems.values());
    }

    // ------------------------------------------------------------------ classifications

    private static void classifications(CompiledSchema before, CompiledSchema after, Clearance clearance,
            Map<String, Problem> problems) {
        for (CompiledSchema.Declaration declaration : after.declarations().values()) {
            Attribute attribute = declaration.attribute();
            CompiledSchema.Declaration earlier = before.declarations().get(attribute.id());
            Optional<String> was = earlier == null ? Optional.empty() : earlier.attribute().classification();
            Optional<String> is = attribute.classification();
            if (was.equals(is)) {
                continue;
            }
            Optional<String> uncovered = was.filter(classification -> !covers(clearance, classification))
                    .or(() -> is.filter(classification -> !covers(clearance, classification)));
            if (uncovered.isEmpty()) {
                continue;
            }
            String path = Names.path(attribute.declaredBy(), attribute.name());
            String change = was.isEmpty() ? "adds the classification " + is.orElseThrow()
                    : is.isEmpty() ? "removes the classification " + was.get()
                    : "changes the classification from " + was.get() + " to " + is.get();
            problems.putIfAbsent(path, denied(path, "The registration " + change + " to the attribute "
                    + attribute.name() + " of " + attribute.declaredBy() + ", but the clearance of the caller does not "
                    + "cover " + uncovered.get() + ", so the caller may not change who can read the attribute.",
                    uncovered.get(), clearance, "Ask a person whose clearance covers " + uncovered.get()
                    + " to change the classification."));
        }
    }

    // ------------------------------------------------------------------ checks

    private static void checks(CompiledSchema before, CompiledSchema after, Clearance clearance,
            Map<String, Problem> problems) {
        Map<String, String> known = new HashMap<>();
        forEachType(before, (type, attributes) -> before.checks(type).forEach(applied -> known.put(
                identity(applied), applied.check().check().expression())));
        forEachType(after, (type, attributes) -> {
            for (AppliedCheck applied : after.checks(type)) {
                String expression = applied.check().check().expression();
                if (expression.equals(known.get(identity(applied)))) {
                    continue;
                }
                Optional<Attribute> read = uncoveredRead(applied.reads(attributes), attributes, clearance);
                if (read.isPresent()) {
                    String path = Names.path(applied.check().declaredBy(), applied.check().check().name());
                    problems.putIfAbsent(path, denied(path, "The registration " + (known.containsKey(identity(applied))
                            ? "changes" : "adds") + " the check " + applied.check().check().name() + " of "
                            + applied.check().declaredBy() + ", which reads the attribute " + read.get().name()
                            + ", classified " + read.get().classification().orElseThrow() + ", but the clearance of the "
                            + "caller does not cover " + read.get().classification().orElseThrow() + ", so the check "
                            + "would let the caller probe a value that it may not read.",
                            read.get().classification().orElseThrow(), clearance,
                            "Ask a person whose clearance covers " + read.get().classification().orElseThrow()
                            + " to add the check."));
                }
            }
        });
    }

    private static String identity(AppliedCheck applied) {
        return applied.check().declaredBy() + "|" + applied.check().check().name();
    }

    // ------------------------------------------------------------------ derived attributes

    private static void derivations(CompiledSchema before, CompiledSchema after, Clearance clearance,
            Map<String, Problem> problems) {
        Set<AttributeId> reported = new HashSet<>();
        forEachType(after, (type, attributes) -> {
            for (AppliedDerivation applied : after.derivations(type)) {
                Attribute derived = applied.attribute();
                CompiledSchema.Declaration earlier = before.declarations().get(derived.id());
                Optional<String> was = earlier == null ? Optional.empty() : earlier.attribute().derived();
                if (derived.derived().equals(was) || !reported.add(derived.id())) {
                    continue;
                }
                Optional<Attribute> read = uncoveredRead(applied.reads(), attributes, clearance);
                if (read.isPresent()) {
                    String path = Names.path(derived.declaredBy(), derived.name());
                    problems.putIfAbsent(path, denied(path, "The registration " + (was.isPresent() ? "changes the "
                            + "expression of" : "adds") + " the derived attribute " + derived.name() + " of "
                            + derived.declaredBy() + ", which reads the attribute " + read.get().name()
                            + ", classified " + read.get().classification().orElseThrow() + ", but the clearance of the "
                            + "caller does not cover " + read.get().classification().orElseThrow() + ".",
                            read.get().classification().orElseThrow(), clearance,
                            "Ask a person whose clearance covers " + read.get().classification().orElseThrow()
                            + " to make the change."));
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Calls an action for every entity type and relationship type of a schema with its attributes. */
    private static void forEachType(CompiledSchema schema,
            BiConsumer<TypeId, List<Attribute>> action) {
        schema.entityTypes().forEach(type -> action.accept(type.id(), type.attributes()));
        schema.relationshipTypes().forEach(type -> action.accept(type.id(), type.attributes()));
    }

    /** The first attribute among those read whose classification the clearance does not cover. */
    private static Optional<Attribute> uncoveredRead(Set<String> names, List<Attribute> attributes,
            Clearance clearance) {
        Map<String, Attribute> byName = attributes.stream().collect(Collectors.toMap(
                attribute -> attribute.name().toLowerCase(Locale.ROOT), Function.identity(), (first, second) -> first));
        return names.stream().sorted().map(name -> byName.get(name.toLowerCase(Locale.ROOT)))
                .filter(attribute -> attribute != null && !clearance.covers(attribute)).findFirst();
    }

    private static boolean covers(Clearance clearance, String classification) {
        return clearance.classifications().contains(classification);
    }

    private static Problem denied(String path, String message, String classification, Clearance clearance, String hint) {
        Set<String> covered = clearance.classifications();
        return Problems.of(ProblemCode.ACCESS_DENIED, path, message, "a caller whose clearance covers " + classification,
                covered.isEmpty() ? "a clearance that covers no classification"
                        : "a clearance that covers " + String.join(", ", covered), hint);
    }
}
