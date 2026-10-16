package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.expressions.ExpressionCompilationException;
import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
     * @param compiler  the compiler of the registry, which tells what the checks and derivations of every type read
     * @return the problems, in the order of their paths; none if the clearance allows the registration
     */
    static List<Problem> problems(CompiledSchema before, CompiledSchema after, Clearance clearance,
            ExpressionCompiler compiler) {
        Map<String, Problem> problems = new TreeMap<>();
        classifications(before, after, clearance, problems);
        checks(before, after, clearance, compiler, problems);
        derivations(before, after, clearance, compiler, problems);
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

    /**
     * Guards the checks that the types declare, every type's own, whether it is abstract or not: the schema keeps compiled
     * checks for concrete types only, but a check that an abstract type declares applies to every concrete type that
     * is added below it later. What a check reads is found by compiling it for the attributes of the type that declares
     * it, as validation does.
     */
    private static void checks(CompiledSchema before, CompiledSchema after, Clearance clearance,
            ExpressionCompiler compiler, Map<String, Problem> problems) {
        Map<String, String> known = new HashMap<>();
        forEachType(before, (name, attributes, checks) -> checks
                .forEach(check -> known.put(Names.path(name, check.name()), check.expression())));
        forEachType(after, (name, attributes, checks) -> {
            for (Check check : checks) {
                String path = Names.path(name, check.name());
                if (check.expression().equals(known.get(path))) {
                    continue;
                }
                Optional<Attribute> read = uncoveredRead(checkReads(compiler, check.expression(), attributes),
                        attributes, clearance);
                if (read.isPresent()) {
                    problems.putIfAbsent(path, denied(path, "The registration " + (known.containsKey(path)
                            ? "changes" : "adds") + " the check " + check.name() + " of " + name
                            + ", which reads the attribute " + read.get().name() + ", classified "
                            + read.get().classification().orElseThrow() + ", but the clearance of the caller does not "
                            + "cover " + read.get().classification().orElseThrow() + ", so the check would let the "
                            + "caller probe a value that it may not read.",
                            read.get().classification().orElseThrow(), clearance,
                            "Ask a person whose clearance covers " + read.get().classification().orElseThrow()
                            + " to add the check."));
                }
            }
        });
    }

    /** The attributes that a check reads when it is compiled for a type; all of the type's if that cannot be told. */
    private static Set<String> checkReads(ExpressionCompiler compiler, String expression, List<Attribute> attributes) {
        Set<String> all = attributes.stream().map(Attribute::name).collect(Collectors.toSet());
        if (!compiler.available()) {
            return all;
        }
        Map<String, ValueType> types = new LinkedHashMap<>();
        attributes.forEach(attribute -> types.put(attribute.name(), attribute.valueType()));
        try {
            return compiler.compile(expression, types).attributesRead().orElse(all);
        } catch (ExpressionCompilationException | RuntimeException e) {
            return all;
        }
    }

    // ------------------------------------------------------------------ derived attributes

    /**
     * Guards the derived attributes that the types have, abstract types included, for the same reason as the checks: the
     * schema compiles derivations for concrete types only. What a derivation reads is found by compiling it for the
     * attributes of the type that are not derived, as validation does for a concrete type.
     */
    private static void derivations(CompiledSchema before, CompiledSchema after, Clearance clearance,
            ExpressionCompiler compiler, Map<String, Problem> problems) {
        forEachType(after, (name, attributes, checks) -> {
            for (Attribute derived : attributes) {
                if (derived.derived().isEmpty()) {
                    continue;
                }
                CompiledSchema.Declaration earlier = before.declarations().get(derived.id());
                Optional<String> was = earlier == null ? Optional.empty() : earlier.attribute().derived();
                if (derived.derived().equals(was)) {
                    continue;
                }
                Optional<Attribute> read = uncoveredRead(derivationReads(compiler, derived, attributes), attributes,
                        clearance);
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

    /** The attributes that a derivation reads when it is compiled for a type; all of the type's if that cannot be told. */
    private static Set<String> derivationReads(ExpressionCompiler compiler, Attribute derived,
            List<Attribute> attributes) {
        Set<String> all = attributes.stream().map(Attribute::name).collect(Collectors.toSet());
        if (!compiler.available()) {
            return all;
        }
        Map<String, ValueType> readable = new LinkedHashMap<>();
        attributes.stream().filter(attribute -> attribute.derived().isEmpty())
                .forEach(attribute -> readable.put(attribute.name(), attribute.valueType()));
        try {
            return compiler.compileDerivation(derived.derived().orElseThrow(), readable, derived.valueType())
                    .attributesRead();
        } catch (ExpressionCompilationException | RuntimeException e) {
            return all;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** What an action is given for a type: its name, all of its attributes and the checks that it declares. */
    private interface TypeAction {
        void accept(String name, List<Attribute> attributes, List<Check> checks);
    }

    /** Calls an action for every entity type, abstract or not, and every relationship type of a schema. */
    private static void forEachType(CompiledSchema schema, TypeAction action) {
        schema.entityTypes().forEach(type -> action.accept(type.name(), type.attributes(), type.checks()));
        schema.relationshipTypes().forEach(type -> action.accept(type.name(), type.attributes(), type.checks()));
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
