package io.github.enricoag1982.cairn.core.schema;

import io.github.enricoag1982.cairn.core.commit.CommitSequence;
import io.github.enricoag1982.cairn.core.storage.Journal;
import io.github.enricoag1982.cairn.core.storage.SchemaIdentifiers;
import io.github.enricoag1982.cairn.core.storage.SchemaRecord;
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
import io.github.enricoag1982.cairn.schema.StoredDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import java.time.Instant;
import java.time.InstantSource;
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
 *
 * <p>The registry takes the basis and the transaction time of every applied registration from a
 * {@link CommitSequence}, and holds that sequence's writer lock while it registers. A stand-alone registry has a
 * sequence of its own; the registry of a store shares the store's sequence, so schema transactions and data
 * transactions are ordered together and the bases of the schema history are not consecutive.
 *
 * <p>The registry of a durable store has a {@linkplain Journal journal}: an applied registration is written to it, as a
 * {@link SchemaRecord}, after it is compiled and before its new state is published, and a registration is refused while
 * the journal refuses writes. {@link #restore(SchemaRecord)} applies a registration that a log or snapshot recorded,
 * without validating it and without the sequence.
 */
public final class InMemorySchemaRegistry implements SchemaRegistry {

    private record OriginState(Origin origin, SchemaDefinition definition) {
    }

    private record State(
            CompiledSchema schema,
            Map<String, OriginState> origins,
            List<SchemaTransaction> history,
            List<SchemaRecord> records,
            long nextId) {
    }

    /**
     * A plan, and, if its outcome is to apply, what applying it needs: the origins and the schema it would produce.
     */
    private record Planned(SchemaPlan plan, Map<String, OriginState> origins, SchemaCompiler.Result compiled) {
        static Planned of(SchemaPlan plan) {
            return new Planned(plan, Map.of(), null);
        }
    }

    private final CommitSequence sequence;
    private final List<RuleDescriptor> rules;
    private volatile State state = new State(CompiledSchema.EMPTY, Map.of(), List.of(), List.of(), 1);
    /** Where applied registrations are written before they are published; null if the registry is not durable. */
    private volatile Journal journal;

    /**
     * Creates an empty registry without available rules.
     *
     * @param clock the source of transaction times
     */
    public InMemorySchemaRegistry(InstantSource clock) {
        this(clock, List.of());
    }

    /**
     * Creates an empty stand-alone registry, with a commit sequence of its own.
     *
     * @param clock the source of transaction times
     * @param rules the rules that types may name
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two
     *                                  rules have the same name and version
     */
    public InMemorySchemaRegistry(InstantSource clock, Collection<RuleDescriptor> rules) {
        this(new CommitSequence(clock), rules);
    }

    /**
     * Creates an empty registry on a commit sequence, which other writers may share.
     *
     * @param sequence the sequence that supplies bases and transaction times, and the writer lock
     * @param rules    the rules that types may name
     * @throws IllegalArgumentException if a rule's name or version breaks the rules for rule references, or if two
     *                                  rules have the same name and version
     */
    public InMemorySchemaRegistry(CommitSequence sequence, Collection<RuleDescriptor> rules) {
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        List<RuleDescriptor> sorted = Objects.requireNonNull(rules, "rules").stream()
                .map(rule -> Objects.requireNonNull(rule, "rule"))
                .sorted(Comparator.comparing(RuleDescriptor::name).thenComparingInt(RuleDescriptor::version))
                .toList();
        for (RuleDescriptor rule : sorted) {
            if (!Names.isValidRuleName(rule.name())) {
                throw new IllegalArgumentException("The rule name '" + rule.name() + "' must be "
                        + Names.RULE_NAME_FORM + ".");
            }
            if (rule.version() < 1) {
                throw new IllegalArgumentException("The version " + rule.version() + " of the rule " + rule.name()
                        + " must be a whole number of at least 1.");
            }
        }
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
    public RegistrationResult register(Origin origin, SchemaDefinition definition, Submission submission) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(submission, "submission");
        return sequence.underWriterLock(() -> {
            Journal written = journal;
            if (written != null) {
                written.requireWritable();
            }
            State current = state;
            Planned planned = plan(current, origin, definition);
            return switch (planned.plan().outcome()) {
                case REJECT -> new RegistrationResult.Rejected(planned.plan().problems());
                case UNCHANGED -> new RegistrationResult.Unchanged(current.schema());
                case APPLY -> apply(current, origin, submission, planned);
            };
        });
    }

    @Override
    public SchemaPlan plan(Origin origin, SchemaDefinition definition) {
        return plan(state, Objects.requireNonNull(origin, "origin"), Objects.requireNonNull(definition, "definition"))
                .plan();
    }

    /**
     * Records an accepted registration as a schema transaction. Called with the writer lock held. A durable registry
     * writes the registration to its journal first, so that nothing is applied if that fails. The new state is in place
     * before the commit is published, so whoever learns of the basis finds the schema.
     */
    private RegistrationResult apply(State current, Origin origin, Submission submission, Planned planned) {
        SchemaCompiler.Result compiled = planned.compiled();
        CommitSequence.Commit commit = sequence.next();
        Basis basis = commit.basis();
        Instant time = commit.transactionTime();
        SchemaRecord record = new SchemaRecord(basis, time, submission, origin,
                planned.origins().get(origin.name()).definition(),
                identifiers(compiled.schema(), compiled.nextId()));
        Journal written = journal;
        if (written != null) {
            written.append(record);
        }
        state = next(current, planned.origins(), compiled, record);
        sequence.publish(commit);
        return new RegistrationResult.Applied(time, basis, compiled.schema());
    }

    /** The state after a registration: its schema transaction and its record are added to the history. */
    private static State next(State current, Map<String, OriginState> origins, SchemaCompiler.Result compiled,
            SchemaRecord record) {
        List<SchemaTransaction> history = new ArrayList<>(current.history());
        history.add(new SchemaTransaction(record.basis(), record.transactionTime(), record.origin(),
                compiled.schema(), record.submission().principal(), record.submission().onBehalfOf(),
                record.submission().reason()));
        List<SchemaRecord> records = new ArrayList<>(current.records());
        records.add(record);
        return new State(compiled.schema(), Map.copyOf(origins), List.copyOf(history), List.copyOf(records),
                compiled.nextId());
    }

    /**
     * The identifiers of every type, trait, relationship type and attribute of the schema after a registration, and the
     * first identifier that is free. Recovery takes all of them from the record, and matches nothing by name.
     */
    private static SchemaIdentifiers identifiers(CompiledSchema schema, long nextId) {
        List<SchemaIdentifiers.TypeAssignment> types = schema.entries().stream()
                .sorted(Comparator.comparingLong(entry -> entry.id().value()))
                .map(entry -> new SchemaIdentifiers.TypeAssignment(entry.name(), entry.id()))
                .toList();
        List<SchemaIdentifiers.AttributeAssignment> attributes = schema.declarations().values().stream()
                .map(CompiledSchema.Declaration::attribute)
                .sorted(Comparator.comparingLong(attribute -> attribute.id().value()))
                .map(attribute -> new SchemaIdentifiers.AttributeAssignment(attribute.declaredBy(), attribute.name(),
                        attribute.id()))
                .toList();
        return new SchemaIdentifiers(types, attributes, nextId);
    }

    /**
     * Makes the registry write every registration it applies to a journal before it publishes it, and refuse
     * registrations while the journal refuses writes. Call it once, before the registry is used by other threads.
     *
     * @param journal where applied registrations are written
     */
    public void attach(Journal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /**
     * Applies a registration that a log or snapshot file recorded, as recovery does (design D4 of
     * {@code add-durable-commit-log}): the definition is compiled, with the identifier that the record holds for every
     * element and without matching names, and added to the history. Nothing is validated or planned, the sequence is not touched, and nothing is written to
     * the journal. Registrations are restored in the order of their bases, before the registry is used.
     *
     * @param record the registration
     * @throws IllegalStateException if the record has no identifier for an element
     */
    public void restore(SchemaRecord record) {
        Objects.requireNonNull(record, "record");
        State current = state;
        Map<String, OriginState> origins = new HashMap<>(current.origins());
        origins.put(record.origin().name(), new OriginState(record.origin(), record.definition()));
        Map<String, Origin> originOfType = new HashMap<>();
        origins.values().forEach(origin -> typeNames(origin.definition())
                .forEach(name -> originOfType.put(name, origin.origin())));
        List<Origin> list = origins.values().stream().map(OriginState::origin).toList();
        SchemaCompiler.Result compiled = SchemaCompiler.restore(combined(origins),
                name -> originOfType.get(Names.key(name)), list, record.identifiers());
        state = next(current, origins, compiled, record);
    }

    /**
     * The registrations that were applied, as the log records them, oldest first.
     *
     * @return the records of the schema history, with the definition and the identifiers of each
     */
    public List<SchemaRecord> records() {
        return state.records();
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
    public Optional<StoredDefinition> storedDefinition(String originName) {
        OriginState stored = state.origins().get(Objects.requireNonNull(originName, "originName"));
        return Optional.ofNullable(stored).map(origin -> new StoredDefinition(origin.origin(), origin.definition()));
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
        List<Problem> breaking = breaking(changes, true);
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
     * those from the older version to the stored one: what the stored version adds. If it did not evolve compatibly,
     * the problems carry no hints, because the hints of the changes tell how to avoid a change going forward from the
     * stored version, which is the wrong way here.
     */
    private static Planned rollback(State current, Origin origin, SchemaDefinition definition) {
        Map<String, OriginState> submitted = new HashMap<>(current.origins());
        submitted.put(origin.name(), new OriginState(origin, definition));
        SchemaCompiler.Result older = compile(submitted, CompiledSchema.EMPTY, 1);
        SchemaCompiler.Result stored = compile(current.origins(), older.schema(), older.nextId());
        List<SchemaChange> changes = CompatibilityChecker.diff(older.schema(), stored.schema(), origin.name());
        List<Problem> breaking = breaking(changes, false);
        if (breaking.isEmpty()) {
            return Planned.of(new SchemaPlan(SchemaPlan.Outcome.UNCHANGED, changes, List.of()));
        }
        List<Problem> problems = new ArrayList<>();
        problems.add(new Problem(ProblemCode.BREAKING_CHANGE, OriginRules.PATH, "Origin " + origin.name() + " version "
                + origin.version() + " is older than the stored version, which is not a compatible evolution of it."));
        problems.addAll(breaking);
        return Planned.of(new SchemaPlan(SchemaPlan.Outcome.REJECT, changes, problems));
    }

    /** The problems for the breaking changes; the hint of a change is passed on only if {@code withHints}. */
    private static List<Problem> breaking(List<SchemaChange> changes, boolean withHints) {
        return changes.stream()
                .filter(change -> !change.compatible())
                .map(change -> new Problem(ProblemCode.BREAKING_CHANGE, change.path(), change.description(),
                        Optional.empty(), Optional.empty(), withHints ? change.hint() : Optional.empty()))
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
}
