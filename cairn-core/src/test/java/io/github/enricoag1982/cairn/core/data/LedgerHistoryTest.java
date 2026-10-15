package io.github.enricoag1982.cairn.core.data;

import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static io.github.enricoag1982.cairn.schema.Multiplicity.ZERO_OR_MORE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.IdGenerators;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Id;
import io.github.enricoag1982.cairn.data.RelationshipId;
import io.github.enricoag1982.cairn.data.RelationshipState;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.TestClock;
import io.github.enricoag1982.cairn.schema.AttributeId;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.Schema;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TypeId;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A view at every basis reads the timelines that the transactions left, as it did before entities and relationships were
 * kept as keys (ADR-0027, design D4 of {@code refactor-storage-onto-key-value-store}). A {@link LedgerOracle}, attached
 * to the store as its journal, keeps the whole timeline that each transaction left each entity and relationship with, in
 * a map by basis; the tests compare what the {@link Ledger} rebuilds from rows and markers with it, for every basis, for
 * the stories of the reference domain and for random transactions on a schema with subtypes, a symmetric relationship
 * type and unique attributes of several numeric forms.
 */
class LedgerHistoryTest {

    /** Compares everything the ledger knows with what the oracle recorded, at every basis. */
    private static void assertEveryBasisReadsAsRecorded(Ledger ledger, LedgerOracle oracle) {
        long latest = oracle.latest();
        for (LedgerOracle.Recorded entry : oracle.all()) {
            for (long value = 0; value <= latest + 1; value++) {
                Basis basis = new Basis(value);
                Optional<Held> held = entry.id instanceof EntityId id ? ledger.entity(id, basis)
                        : ledger.relationship((RelationshipId) entry.id, basis);
                boolean recorded = entry.versions.firstKey() <= value;
                assertThat(held.isPresent()).as("%s exists at basis %d", entry.id, value).isEqualTo(recorded);
                assertThat(ledger.timelineAt(entry.id, basis)).as("%s at basis %d", entry.id, value)
                        .isEqualTo(oracle.at(entry.id, basis));
                if (recorded) {
                    Held found = held.orElseThrow();
                    assertThat(found.type()).isEqualTo(entry.type);
                    assertThat(found.source()).isEqualTo(entry.source);
                    assertThat(found.target()).isEqualTo(entry.target);
                    assertThat(found.basis().value()).as("last change of %s at basis %d", entry.id, value)
                            .isEqualTo(oracle.lastChange(entry.id, basis));
                }
            }
            List<Ledger.Version> versions = ledger.versions(entry.id, new Basis(latest + 1));
            assertThat(versions).as("the versions of %s", entry.id).extracting(version -> version.basis().value())
                    .containsExactlyElementsOf(entry.versions.keySet());
            for (Ledger.Version version : versions) {
                assertThat(version.timeline()).isEqualTo(entry.versions.get(version.basis().value()));
            }
            long middle = entry.versions.lastKey() - 1;
            assertThat(ledger.versions(entry.id, new Basis(middle))).as("the versions of %s up to basis %d",
                    entry.id, middle).extracting(version -> version.basis().value())
                    .containsExactlyElementsOf(entry.versions.headMap(middle, true).keySet());
            Held latestHeld = ledger.held(entry.id).orElseThrow();
            assertThat(latestHeld.timeline()).isEqualTo(entry.versions.lastEntry().getValue());
            assertThat(latestHeld.basis().value()).isEqualTo(entry.versions.lastKey());
        }
        assertThat(ledger.entityIds()).containsExactlyElementsOf(oracle.all().stream().map(entry -> entry.id)
                .filter(EntityId.class::isInstance).map(EntityId.class::cast).sorted().toList());
        assertThat(ledger.relationshipIds()).containsExactlyElementsOf(oracle.all().stream().map(entry -> entry.id)
                .filter(RelationshipId.class::isInstance).map(RelationshipId.class::cast).sorted().toList());
    }

    // ------------------------------------------------------------------ the stories of the reference domain

    @Test
    void everyBasisOfTheReferenceStoriesReadsAsTheTransactionsLeftIt() {
        TestClock clock = new TestClock();
        InMemoryStore store = new InMemoryStore(clock, IdGenerators.deterministic(),
                ReferenceModel.executableRules(), ReferenceModel.declaredRules());
        LedgerOracle oracle = new LedgerOracle();
        store.attach(oracle);
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), ReferenceModel.definition()))
                .isInstanceOf(RegistrationResult.Applied.class);

        ReferenceTales.Told told = ReferenceTales.everyStory(store, clock);

        assertThat(told.ids()).isNotEmpty();
        assertThat(oracle.all()).as("everything the stories created").hasSizeGreaterThan(10);
        assertEveryBasisReadsAsRecorded(store.ledger(), oracle);
        assertViewsReadTheRecordedTimelines(store, oracle);
    }

    /** A view at each basis and effective time shows the segment of the timeline that the oracle recorded then. */
    private static void assertViewsReadTheRecordedTimelines(InMemoryStore store, LedgerOracle oracle) {
        Schema schema = store.schema().current();
        Set<TypeId> parties = new SchemaIndex(schema).typeAndSubtypes(schema.entityType("Party").orElseThrow());
        for (long value = 0; value <= oracle.latest() + 1; value++) {
            Basis basis = new Basis(value);
            for (Instant effective : ReferenceTales.EFFECTIVE_TIMES) {
                View view = store.view(Coordinates.effectiveAt(effective).knownAt(basis));
                List<EntityId> listed = new ArrayList<>();
                for (LedgerOracle.Recorded entry : oracle.all()) {
                    Optional<Segment> expected = oracle.at(entry.id, view.basis()).at(effective);
                    if (entry.id instanceof EntityId id) {
                        Optional<EntityState> state = view.entity(id);
                        assertThat(state.isPresent()).as("%s at %s, basis %d", id, effective, value)
                                .isEqualTo(expected.isPresent());
                        state.ifPresent(found -> assertThat(found.recordedBasis())
                                .isEqualTo(expected.orElseThrow().recordedBasis()));
                        if (expected.isPresent() && parties.contains(entry.type)) {
                            listed.add(id);
                        }
                    } else {
                        Optional<RelationshipState> state = view.relationship((RelationshipId) entry.id);
                        assertThat(state.isPresent()).as("%s at %s, basis %d", entry.id, effective, value)
                                .isEqualTo(expected.isPresent());
                    }
                }
                listed.sort(null);
                if (view.schema().entityType("Party").isPresent()) {
                    assertThat(view.entities("Party")).extracting(EntityState::id)
                            .as("the parties at %s, basis %d", effective, value).containsExactlyElementsOf(listed);
                }
            }
        }
    }

    // ------------------------------------------------------------------ random histories

    /** The identifiers that the random histories compare indexes by. */
    private record TypeIds(TypeId thing, TypeId part, TypeId near, TypeId uses, AttributeId code, AttributeId rank) {
    }

    private static SchemaDefinition randomSchema() {
        return SchemaDefinition.builder()
                .entityType("Thing", e -> e
                        .attribute("code", STRING, a -> a.unique())
                        .attribute("rank", DECIMAL, a -> a.unique())
                        .attribute("note", STRING))
                .entityType("Part", e -> e.supertype("Thing").attribute("size", INT))
                .relationshipType("Near", r -> r.from("Thing").to("Thing").symmetric()
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE).inverse("nearTo")
                        .attribute("weight", INT))
                .relationshipType("Uses", r -> r.from("Thing").to("Part")
                        .targetsPerSource(ZERO_OR_MORE).sourcesPerTarget(ZERO_OR_MORE).inverse("usedBy")
                        .attribute("weight", INT))
                .build();
    }

    /** A number between 0 and 24 as an int, a long or a decimal of one of two scales, which are all the same number. */
    private static Object rank(Random random) {
        int number = random.nextInt(25);
        return switch (random.nextInt(4)) {
            case 0 -> number;
            case 1 -> (long) number;
            case 2 -> new BigDecimal(number + ".0");
            default -> new BigDecimal(number + ".00");
        };
    }

    private static Map<String, Value> randomValues(Random random, boolean part) {
        Map<String, Value> values = new LinkedHashMap<>();
        if (random.nextInt(3) != 0) {
            values.put("code", Value.of("c" + random.nextInt(20)));
        }
        if (random.nextInt(3) == 0) {
            values.putAll(values("rank", rank(random)));
        }
        if (random.nextBoolean()) {
            values.put("note", Value.of("n" + random.nextInt(40)));
        }
        if (part && random.nextBoolean()) {
            values.put("size", Value.of(random.nextInt(9)));
        }
        return values;
    }

    /** A period that starts on a random day of a month and ends some days later, or does not end. */
    private static EffectivePeriod randomPeriod(Random random, int firstMonth, int openOneIn) {
        Instant from = on(firstMonth + random.nextInt(3), 1 + random.nextInt(25));
        return random.nextInt(openOneIn) == 0 ? EffectivePeriod.from(from)
                : EffectivePeriod.between(from, from.plus(Duration.ofDays(5 + random.nextInt(70))));
    }

    private static <T> T pick(Random random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** One random transaction of up to three writes against what exists. */
    private static Transaction randomTransaction(Random random, List<EntityId> entities,
            List<RelationshipId> relationships) {
        Transaction.Builder builder = Transaction.by("random");
        int writes = 1 + random.nextInt(3);
        for (int i = 0; i < writes; i++) {
            int choice = entities.size() < 4 ? 0 : entities.size() >= 12 ? 3 + random.nextInt(9) : random.nextInt(12);
            switch (choice) {
                case 0, 1, 2 -> {
                    boolean part = random.nextBoolean();
                    EffectivePeriod period = random.nextInt(8) == 0
                            ? EffectivePeriod.between(on(1, 1), on(8 + random.nextInt(4), 1 + random.nextInt(25)))
                            : EffectivePeriod.from(on(1, 1));
                    builder.create(part ? "Part" : "Thing", period, randomValues(random, part));
                }
                case 3, 4, 5 -> builder.patch(pick(random, entities), randomPeriod(random, 2, 6),
                        randomValues(random, false));
                case 6 -> builder.replace(pick(random, entities), randomPeriod(random, 2, 6),
                        randomValues(random, false));
                case 7 -> builder.endDate(pick(random, entities), on(6 + random.nextInt(6), 1 + random.nextInt(25)));
                case 8, 9 -> builder.relate(random.nextBoolean() ? "Near" : "Uses", pick(random, entities),
                        pick(random, entities), randomPeriod(random, 2, 5),
                        Map.of("weight", Value.of(random.nextInt(5))));
                default -> {
                    if (relationships.isEmpty()) {
                        break;
                    }
                    if (random.nextBoolean()) {
                        builder.patch(pick(random, relationships), randomPeriod(random, 2, 5),
                                Map.of("weight", Value.of(random.nextInt(5))));
                    } else {
                        builder.endDate(pick(random, relationships), on(4 + random.nextInt(4), 1 + random.nextInt(25)));
                    }
                }
            }
        }
        return builder.build();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyBasisOfRandomHistoriesReadsAsTheTransactionsLeftIt(long seed) {
        Random random = new Random(seed);
        TestClock clock = new TestClock();
        InMemoryStore store = new InMemoryStore(clock, IdGenerators.deterministic(), List.of(), List.of());
        LedgerOracle oracle = new LedgerOracle();
        store.attach(oracle);
        assertThat(store.schema().register(ReferenceModel.origin("1.0.0"), randomSchema()))
                .isInstanceOf(RegistrationResult.Applied.class);
        Schema schema = store.schema().current();
        TypeIds ids = new TypeIds(schema.entityType("Thing").orElseThrow().id(),
                schema.entityType("Part").orElseThrow().id(), schema.relationshipType("Near").orElseThrow().id(),
                schema.relationshipType("Uses").orElseThrow().id(),
                schema.entityType("Thing").orElseThrow().attribute("code").orElseThrow().id(),
                schema.entityType("Thing").orElseThrow().attribute("rank").orElseThrow().id());
        List<EntityId> entities = new ArrayList<>();
        List<RelationshipId> relationships = new ArrayList<>();
        int accepted = 0;

        for (int step = 0; step < 320; step++) {
            clock.advance(Duration.ofHours(7));
            CommitResult result = store.commit(randomTransaction(random, entities, relationships));
            if (result instanceof CommitResult.Committed committed) {
                accepted++;
                for (Id created : committed.receipt().created()) {
                    if (created instanceof EntityId id) {
                        entities.add(id);
                    } else {
                        relationships.add((RelationshipId) created);
                    }
                }
            }
            if (step % 100 == 99) {
                assertEveryBasisReadsAsRecorded(store.ledger(), oracle);
            }
        }

        assertThat(accepted).as("transactions that the store accepted").isGreaterThan(100);
        assertThat(oracle.all()).as("entities and relationships that were recorded").hasSizeGreaterThan(20);
        assertEveryBasisReadsAsRecorded(store.ledger(), oracle);
        assertIndexesReadAsRecorded(store.ledger(), oracle, ids, entities);
    }

    /** Listings, the ends of entities and unique candidates at every basis are what the recorded timelines say. */
    private static void assertIndexesReadAsRecorded(Ledger ledger, LedgerOracle oracle, TypeIds ids,
            List<EntityId> entities) {
        List<Value> codes = new ArrayList<>();
        List<Value> ranks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            codes.add(Value.of("c" + i));
            ranks.add(i % 3 == 0 ? Value.of(i) : i % 3 == 1 ? Value.of((long) i) : Value.of(new BigDecimal(i + ".000")));
        }
        ranks.add(Value.of(new BigDecimal("1.50")));
        for (long value = 0; value <= oracle.latest() + 1; value++) {
            Basis basis = new Basis(value);
            assertThat(ledger.entities(Set.of(ids.thing(), ids.part()), basis)).extracting(Held::id)
                    .containsExactlyElementsOf(oracle.entities(Set.of(ids.thing(), ids.part()), basis));
            assertThat(ledger.entities(Set.of(ids.part()), basis)).extracting(Held::id)
                    .containsExactlyElementsOf(oracle.entities(Set.of(ids.part()), basis));
            for (Value code : codes) {
                assertThat(ledger.uniqueIds(ids.code(), code, basis)).as("%s at basis %d", code, value)
                        .containsExactlyElementsOf(oracle.held(ids.code(), code, basis));
            }
            for (Value rank : ranks) {
                assertThat(ledger.uniqueIds(ids.rank(), rank, basis)).as("%s at basis %d", rank, value)
                        .containsExactlyElementsOf(oracle.held(ids.rank(), rank, basis));
            }
            if (value % 5 != 0) {
                continue;
            }
            for (EntityId end : entities) {
                for (Ledger.Direction direction : Ledger.Direction.values()) {
                    for (Set<TypeId> types : List.of(Set.of(ids.near()), Set.of(ids.uses()),
                            Set.of(ids.near(), ids.uses()))) {
                        assertThat(ledger.relationships(end, types, direction, basis)).extracting(Held::id)
                                .as("%s of %s %s at basis %d", types, end, direction, value)
                                .containsExactlyElementsOf(oracle.relationships(end, types, direction, basis));
                    }
                }
            }
        }
    }
}
