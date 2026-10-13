package io.github.enricoag1982.sysrec.core.schema;

import io.github.enricoag1982.sysrec.schema.Basis;
import io.github.enricoag1982.sysrec.schema.EntityTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Origin;
import io.github.enricoag1982.sysrec.schema.Problem;
import io.github.enricoag1982.sysrec.schema.ProblemCode;
import io.github.enricoag1982.sysrec.schema.RegistrationResult;
import io.github.enricoag1982.sysrec.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.sysrec.schema.Schema;
import io.github.enricoag1982.sysrec.schema.SchemaDefinition;
import io.github.enricoag1982.sysrec.schema.SchemaRegistry;
import io.github.enricoag1982.sysrec.schema.SchemaTransaction;
import io.github.enricoag1982.sysrec.schema.TraitDefinition;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A schema registry that keeps everything in memory (design D7, capability {@code schema-evolution}).
 *
 * <p>Registrations are serialized: this is the single writer. Each applied registration publishes a new immutable
 * state, so reads never lock.
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

    private final InstantSource clock;
    private volatile State state = new State(CompiledSchema.EMPTY, Map.of(), List.of(), 1, 0, null);

    /**
     * Creates an empty registry.
     *
     * @param clock the source of transaction times
     */
    public InMemorySchemaRegistry(InstantSource clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized RegistrationResult register(Origin origin, SchemaDefinition definition) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        State current = state;
        List<Problem> problems = check(current, origin, definition);
        if (!problems.isEmpty()) {
            return new RegistrationResult.Rejected(problems);
        }
        OriginState stored = current.origins().get(origin.name());
        if (stored != null) {
            if (Canonical.equal(stored.definition(), definition)) {
                return new RegistrationResult.Unchanged(current.schema());
            }
            int order = OriginRules.compareVersions(origin.version(), stored.origin().version());
            if (order == 0) {
                return new RegistrationResult.Rejected(List.of(new Problem(ProblemCode.VERSION_NOT_INCREASED,
                        OriginRules.PATH, "Origin " + origin.name() + " version " + origin.version()
                        + " is already registered with a different definition; a changed definition needs a higher version.")));
            }
            if (order < 0) {
                return rollback(current, origin, definition);
            }
        }
        Map<String, OriginState> origins = new HashMap<>(current.origins());
        origins.put(origin.name(), new OriginState(origin, definition));
        SchemaCompiler.Result compiled = compile(origins, current.schema(), current.nextId());
        List<Problem> breaking = breaking(CompatibilityChecker.diff(current.schema(), compiled.schema(), origin.name()));
        if (!breaking.isEmpty()) {
            return new RegistrationResult.Rejected(breaking);
        }
        Instant time = nextTransactionTime(current.lastTime());
        Basis basis = new Basis(current.lastBasis() + 1);
        List<SchemaTransaction> history = new ArrayList<>(current.history());
        history.add(new SchemaTransaction(basis, time, origin, compiled.schema()));
        state = new State(compiled.schema(), Map.copyOf(origins), List.copyOf(history), compiled.nextId(),
                basis.value(), time);
        return new RegistrationResult.Applied(time, basis, compiled.schema());
    }

    @Override
    public List<Problem> validate(Origin origin, SchemaDefinition definition) {
        return check(state, Objects.requireNonNull(origin, "origin"), Objects.requireNonNull(definition, "definition"));
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

    // ------------------------------------------------------------------ checks

    /** Origin rules, ownership of types, and validation of the definition together with the other origins. */
    private static List<Problem> check(State current, Origin origin, SchemaDefinition definition) {
        List<Problem> problems = new ArrayList<>(OriginRules.validate(origin));
        if (!problems.isEmpty()) {
            return problems;
        }
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
        for (Problem problem : SchemaValidator.validate(combined(origins))) {
            if (!(problem.code() == ProblemCode.DUPLICATE_NAME && conflicts.contains(Names.key(problem.path())))) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /** Accepts an older version of an origin only if the stored definition evolved from it compatibly. */
    private RegistrationResult rollback(State current, Origin origin, SchemaDefinition definition) {
        Map<String, OriginState> submitted = new HashMap<>(current.origins());
        submitted.put(origin.name(), new OriginState(origin, definition));
        SchemaCompiler.Result older = compile(submitted, CompiledSchema.EMPTY, 1);
        SchemaCompiler.Result stored = compile(current.origins(), older.schema(), older.nextId());
        List<Problem> breaking = breaking(CompatibilityChecker.diff(older.schema(), stored.schema(), origin.name()));
        if (breaking.isEmpty()) {
            return new RegistrationResult.Unchanged(current.schema());
        }
        List<Problem> problems = new ArrayList<>();
        problems.add(new Problem(ProblemCode.BREAKING_CHANGE, OriginRules.PATH, "Origin " + origin.name() + " version "
                + origin.version() + " is older than the stored version, which is not a compatible evolution of it."));
        problems.addAll(breaking);
        return new RegistrationResult.Rejected(problems);
    }

    private static List<Problem> breaking(List<Change> changes) {
        return changes.stream()
                .filter(Change::breaking)
                .map(change -> new Problem(ProblemCode.BREAKING_CHANGE, change.path(), change.description()))
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
