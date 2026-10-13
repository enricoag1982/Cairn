package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaChange;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.SchemaPlan;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import io.github.enricoag1982.cairn.schema.SchemaTransaction;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A schema registry that keeps everything in memory (design D7, capability {@code schema-evolution}).
 *
 * <p>Registrations are serialized: this is the single writer. Each applied registration publishes a new immutable
 * state, so reads never lock. Registering is planning followed by applying the plan, inside one lock, so a plan and a
 * registration of the same input always agree.
 */
public final class InMemorySchemaRegistry implements SchemaRegistry {

    private record OriginState(Origin origin, SchemaDefinition definition) {
    }

    private record State(
            CompiledSchema schema,
            Map<String, OriginState> origins,
            List<SchemaTransaction> history,
            long nextId,
            long lastBasis,
            Instant lastTime) {
    }

    /**
     * A plan, and, if its outcome is to apply, what applying it needs: the origins and the schema it would produce.
     */
    private record Planned(SchemaPlan plan, Map<String, OriginState> origins, SchemaCompiler.Result compiled) {
        static Planned of(SchemaPlan plan) {
            return new Planned(plan, Map.of(), null);
        }
    }

    private final InstantSource clock;
    private final List<RuleDescriptor> rules;
    private volatile State state = new State(CompiledSchema.EMPTY, Map.of(), List.of(), 1, 0, null);

    /**
     * Creates an empty registry without available rules.
     *
     * @param clock the source of transaction times
     */
    public InMemorySchemaRegistry(InstantSource clock) {
        this(clock, List.of());
    }

    /**
     * Creates an empty registry.
     *
     * @param clock the source of transaction times
     * @param rules the rules that types may name
     * @throws IllegalArgumentException if two rules have the same name and version
     */
    public InMemorySchemaRegistry(InstantSource clock, Collection<RuleDescriptor> rules) {
        this.clock = Objects.requireNonNull(clock, "clock");
        List<RuleDescriptor> sorted = Objects.requireNonNull(rules, "rules").stream()
                .map(rule -> Objects.requireNonNull(rule, "rule"))
                .sorted(Comparator.comparing(RuleDescriptor::name).thenComparingInt(RuleDescriptor::version))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            RuleDescriptor previous = sorted.get(i - 1);
            RuleDescriptor rule = sorted.get(i);
            if (previous.name().equals(rule.name()) && previous.version() == rule.version()) {
                throw new IllegalArgumentException("The rule " + rule.name() + " in version " + rule.version()
                        + " is given more than once.");
            }
        }
        this.rules = sorted;
    }

    @Override
    public synchronized RegistrationResult register(Origin origin, SchemaDefinition definition) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        State current = state;
        Planned planned = plan(current, origin, definition);
        return switch (planned.plan().outcome()) {
            case REJECT -> new RegistrationResult.Rejected(planned.plan().problems());
            case UNCHANGED -> new RegistrationResult.Unchanged(current.schema());
            case APPLY -> apply(current, origin, planned);
        };
    }

    @Override
    public SchemaPlan plan(Origin origin, SchemaDefinition definition) {
        return plan(state, Objects.requireNonNull(origin, "origin"), Objects.requireNonNull(definition, "definition"))
                .plan();
    }

    /** Records an accepted registration as a schema transaction. */
    private RegistrationResult apply(State current, Origin origin, Planned planned) {
        SchemaCompiler.Result compiled = planned.compiled();
        Instant time = nextTransactionTime(current.lastTime());
        Basis basis = new Basis(current.lastBasis() + 1);
        List<SchemaTransaction> history = new ArrayList<>(current.history());
        history.add(new SchemaTransaction(basis, time, origin, compiled.schema()));
        state = new State(compiled.schema(), Map.copyOf(planned.origins()), List.copyOf(history), compiled.nextId(),
                basis.value(), time);
        return new RegistrationResult.Applied(time, basis, compiled.schema());
    }

    @Override
    public List<Problem> validate(Origin origin, SchemaDefinition definition) {
        return check(state, Objects.requireNonNull(origin, "origin"), Objects.requireNonNull(definition, "definition"));
    }

    @Override
    public List<RuleDescriptor> availableRules() {
        return rules;
    }

    @Override
    public Schema current() {
        return state.schema();
    }

    @Override
    public Schema asOf(Basis basis) {
        Schema result = CompiledSchema.EMPTY;
        for (SchemaTransaction transaction : state.history()) {
            if (transaction.basis().compareTo(basis) <= 0) {
                result = transaction.schema();
            }
        }
        return result;
    }

    @Override
    public Schema asOf(Instant transactionTime) {
        Schema result = CompiledSchema.EMPTY;
        for (SchemaTransaction transaction : state.history()) {
            if (!transaction.transactionTime().isAfter(transactionTime)) {
                result = transaction.schema();
            }
        }
        return result;
    }

    @Override
    public List<SchemaTransaction> history() {
        return state.history();
    }

    // ------------------------------------------------------------------ planning

    /**
     * Finds out what registering would do: validation, then the comparison with the stored definition of the origin.
     * Nothing is recorded.
     */
    private Planned plan(State current, Origin origin, SchemaDefinition definition) {
        List<Problem> problems = check(current, origin, definition);
        if (!problems.isEmpty()) {
            return Planned.of(new SchemaPlan(SchemaPlan.Outcome.REJECT, List.of(), problems));
        }
        OriginState stored = current.origins().get(origin.name());
        int order = 1;
        if (stored != null) {
            if (Canonical.equal(stored.definition(), definition)) {
                return Planned.of(new SchemaPlan(SchemaPlan.Outcome.UNCHANGED, List.of(), List.of()));
            }
            order = OriginRules.compareVersions(origin.version(), stored.origin().version());
            if (order < 0) {
                return rollback(current, origin, definition);
            }
        }
        Map<String, OriginState> origins = new HashMap<>(current.origins());
        origins.put(origin.name(), new OriginState(origin, definition));
        SchemaCompiler.Result compiled = compile(origins, current.schema(), current.nextId());
        List<SchemaChange> changes = CompatibilityChecker.diff(current.schema(), compiled.schema(), origin.name());
        if (order == 0) {
            return Planned.of(new SchemaPlan(SchemaPlan.Outcome.REJECT, changes, List.of(new Problem(
                    ProblemCode.VERSION_NOT_INCREASED, OriginRules.PATH, "Origin " + origin.name() + " version "
                    + origin.version() + " is already registered with a different definition; a changed definition "
                    + "needs a higher version."))));
        }
        List<Problem> breaking = breaking(changes);
        if (!breaking.isEmpty()) {
            return Planned.of(new SchemaPlan(SchemaPlan.Outcome.REJECT, changes, breaking));
        }
        return new Planned(new SchemaPlan(SchemaPlan.Outcome.APPLY, changes, List.of()), origins, compiled);
    }

    /** Origin rules, ownership of types, and validation of the definition together with the other origins. */
    private List<Problem> check(State current, Origin origin, SchemaDefinition definition) {
        List<Problem> problems = new ArrayList<>(OriginRules.validate(origin));
        Set<String> conflicts = new HashSet<>();
        for (OriginState other : current.origins().values()) {
            if (other.origin().name().equals(origin.name())) {
                continue;
            }
            Set<String> owned = typeNames(other.definition());
            for (String name : declaredTypeNames(definition)) {
                if (owned.contains(Names.key(name))) {
                    conflicts.add(Names.key(name));
                    problems.add(new Problem(ProblemCode.OWNED_BY_OTHER_ORIGIN, name, "The type " + name
                            + " is owned by origin " + other.origin().name() + " and cannot be defined by " + origin.name() + "."));
                }
            }
        }
        Map<String, OriginState> origins = new HashMap<>(current.origins());
        origins.put(origin.name(), new OriginState(origin, definition));
        for (Problem problem : SchemaValidator.validate(combined(origins), rules)) {
            if (!(problem.code() == ProblemCode.DUPLICATE_NAME && conflicts.contains(Names.key(problem.path())))) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /**
     * Accepts an older version of an origin only if the stored definition evolved from it compatibly. The changes are
     * those from the older version to the stored one: what the stored version adds.
     */
    private static Planned rollback(State current, Origin origin, SchemaDefinition definition) {
        Map<String, OriginState> submitted = new HashMap<>(current.origins());
        submitted.put(origin.name(), new OriginState(origin, definition));
        SchemaCompiler.Result older = compile(submitted, CompiledSchema.EMPTY, 1);
        SchemaCompiler.Result stored = compile(current.origins(), older.schema(), older.nextId());
        List<SchemaChange> changes = CompatibilityChecker.diff(older.schema(), stored.schema(), origin.name());
        List<Problem> breaking = breaking(changes);
        if (breaking.isEmpty()) {
            return Planned.of(new SchemaPlan(SchemaPlan.Outcome.UNCHANGED, changes, List.of()));
        }
        List<Problem> problems = new ArrayList<>();
        problems.add(new Problem(ProblemCode.BREAKING_CHANGE, OriginRules.PATH, "Origin " + origin.name() + " version "
                + origin.version() + " is older than the stored version, which is not a compatible evolution of it."));
        problems.addAll(breaking);
        return Planned.of(new SchemaPlan(SchemaPlan.Outcome.REJECT, changes, problems));
    }

    private static List<Problem> breaking(List<SchemaChange> changes) {
        return changes.stream()
                .filter(change -> !change.compatible())
                .map(change -> new Problem(ProblemCode.BREAKING_CHANGE, change.path(), change.description(),
                        Optional.empty(), Optional.empty(), change.hint()))
                .toList();
    }

    // ------------------------------------------------------------------ compilation

    private static SchemaCompiler.Result compile(Map<String, OriginState> origins, CompiledSchema previous, long nextId) {
        Map<String, Origin> originOfType = new HashMap<>();
        origins.values().forEach(state -> typeNames(state.definition())
                .forEach(name -> originOfType.put(name, state.origin())));
        List<Origin> list = origins.values().stream().map(OriginState::origin).toList();
        return SchemaCompiler.compile(combined(origins), name -> originOfType.get(Names.key(name)), list, previous, nextId);
    }

    private static SchemaDefinition combined(Map<String, OriginState> origins) {
        List<EntityTypeDefinition> entityTypes = new ArrayList<>();
        List<TraitDefinition> traits = new ArrayList<>();
        List<RelationshipTypeDefinition> relationshipTypes = new ArrayList<>();
        origins.values().stream()
                .sorted(Comparator.comparing(state -> state.origin().name()))
                .forEach(state -> {
                    entityTypes.addAll(state.definition().entityTypes());
                    traits.addAll(state.definition().traits());
                    relationshipTypes.addAll(state.definition().relationshipTypes());
                });
        return new SchemaDefinition(entityTypes, traits, relationshipTypes);
    }

    /** Keys of the names of all types in a definition. */
    private static Set<String> typeNames(SchemaDefinition definition) {
        Set<String> names = new HashSet<>();
        declaredTypeNames(definition).forEach(name -> names.add(Names.key(name)));
        return names;
    }

    /** Names and aliases of all types in a definition. */
    private static List<String> declaredTypeNames(SchemaDefinition definition) {
        List<String> names = new ArrayList<>();
        definition.entityTypes().forEach(type -> { names.add(type.name()); names.addAll(type.aliases()); });
        definition.traits().forEach(trait -> { names.add(trait.name()); names.addAll(trait.aliases()); });
        definition.relationshipTypes().forEach(type -> { names.add(type.name()); names.addAll(type.aliases()); });
        return names;
    }

    // ------------------------------------------------------------------ time

    /** Transaction times have microsecond precision and strictly increase, even if the clock does not. */
    private Instant nextTransactionTime(Instant last) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return last != null && !now.isAfter(last) ? last.plus(1, ChronoUnit.MICROS) : now;
    }
}
