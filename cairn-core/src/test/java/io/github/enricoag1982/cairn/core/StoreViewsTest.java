package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.ReferenceStore.ACME;
import static io.github.enricoag1982.cairn.core.ReferenceStore.BETA;
import static io.github.enricoag1982.cairn.core.ReferenceStore.from;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.core.ReferenceStore.on;
import static io.github.enricoag1982.cairn.core.ReferenceStore.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.KnownAt;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Design D4: the coordinates of views, and what they read. */
class StoreViewsTest {

    private final ReferenceStore reference = new ReferenceStore();

    private View view(Instant effectiveAt, KnownAt knownAt) {
        return reference.store.view(new Coordinates(effectiveAt, knownAt));
    }

    // ------------------------------------------------------------------ coordinates

    @Test
    void aViewWithoutABasisReadsAsOfTheLatestBasisWhenItIsCreated() {
        reference.createParties();
        View first = reference.view(on(2, 1));
        Receipt later = reference.commit(Transaction.by("importer").build());

        View second = reference.view(on(2, 1));

        assertThat(first.basis()).isEqualTo(new Basis(2));
        assertThat(second.basis()).isEqualTo(later.basis());
        assertThat(first.basis()).as("the first view does not move").isEqualTo(new Basis(2));
        assertThat(first.effectiveAt()).isEqualTo(on(2, 1));
    }

    @Test
    void theEffectiveTimeIsTruncatedToMicroseconds() {
        View view = reference.view(Instant.parse("2026-02-01T00:00:00.123456789Z"));

        assertThat(view.effectiveAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00.123456Z"));
    }

    @Test
    void aViewAsOfABasisSeesTheSchemaAndTheDataOfThatBasis() {
        Receipt parties = reference.createParties();
        Receipt later = reference.commit(Transaction.by("importer")
                .create(new TemporaryId("c"), "Organization", from(1, 1),
                        values("name", "Late")).build());

        View atParties = view(on(2, 1), KnownAt.basis(parties.basis()));
        View atSchema = view(on(2, 1), KnownAt.basis(new Basis(1)));

        assertThat(atParties.basis()).isEqualTo(parties.basis());
        assertThat(atParties.entities("Organization")).hasSize(2);
        assertThat(atSchema.entities("Organization")).isEmpty();
        assertThat(atSchema.schema().entityType("Organization")).isPresent();
        assertThat(view(on(2, 1), KnownAt.basis(later.basis())).entities("Organization")).hasSize(3);
    }

    @Test
    void aBasisThatDoesNotExistYetReadsAsOfTheLatestBasis() {
        reference.createParties();

        View view = view(on(2, 1), KnownAt.basis(new Basis(9999)));

        assertThat(view.basis()).isEqualTo(reference.store.latestBasis());
        assertThat(view.entities("Party")).hasSize(3);
    }

    @Test
    void theEmptyBasisSeesNothing() {
        reference.createParties();

        View view = view(on(2, 1), KnownAt.basis(Basis.NONE));

        assertThat(view.basis()).isEqualTo(Basis.NONE);
        assertThat(view.schema().isEmpty()).isTrue();
        assertThat(view.entity(id(1))).isEmpty();
        assertThat(view(on(2, 1), KnownAt.basis(new Basis(-5))).basis()).isEqualTo(Basis.NONE);
    }

    @Test
    void aTransactionTimeSelectsTheLatestTransactionRecordedAtOrBeforeIt() {
        Instant schemaTime = reference.store.schema().history().getLast().transactionTime();
        Receipt first = reference.recordedOn(on(1, 6)).commit(Transaction.by("importer").build());
        Receipt second = reference.recordedOn(on(3, 1)).commit(Transaction.by("importer").build());

        assertThat(view(on(2, 1), KnownAt.transactionTime(schemaTime.minusNanos(1000))).basis())
                .isEqualTo(Basis.NONE);
        assertThat(view(on(2, 1), KnownAt.transactionTime(schemaTime)).basis()).isEqualTo(new Basis(1));
        assertThat(view(on(2, 1), KnownAt.transactionTime(on(1, 6).minusNanos(1000))).basis())
                .isEqualTo(new Basis(1));
        assertThat(view(on(2, 1), KnownAt.transactionTime(first.transactionTime())).basis()).isEqualTo(first.basis());
        assertThat(view(on(2, 1), KnownAt.transactionTime(on(2, 1))).basis()).isEqualTo(first.basis());
        assertThat(view(on(2, 1), KnownAt.transactionTime(second.transactionTime())).basis())
                .isEqualTo(second.basis());
        assertThat(view(on(2, 1), KnownAt.transactionTime(on(12, 31))).basis()).isEqualTo(second.basis());
    }

    @Test
    void aTransactionTimeConsidersSchemaTransactionsToo() {
        Receipt data = reference.recordedOn(on(2, 1)).commit(Transaction.by("importer").build());
        RegistrationResult.Applied schema = (RegistrationResult.Applied) reference.recordedOn(on(3, 1))
                .store.schema().register(new Origin("extra", "1"), SchemaDefinition.builder().entityType("X").build());

        View between = view(on(2, 15), KnownAt.transactionTime(on(2, 15)));
        View after = view(on(2, 15), KnownAt.transactionTime(on(3, 2)));

        assertThat(between.basis()).isEqualTo(data.basis());
        assertThat(between.schema().entityType("X")).isEmpty();
        assertThat(after.basis()).isEqualTo(schema.basis());
        assertThat(after.schema().entityType("X")).isPresent();
    }

    // ------------------------------------------------------------------ reading one entity

    @Test
    void anEntityIsFoundInItsPeriodOnly() {
        EntityId id = (EntityId) reference.commit(Transaction.by("importer").create(ACME, "Organization",
                EffectivePeriod.between(on(2, 1), on(4, 1)), values("name", "ACME")).build())
                .temporaryIds().get(ACME);

        assertThat(reference.view(on(1, 31)).entity(id)).isEmpty();
        assertThat(reference.view(on(2, 1)).entity(id)).isPresent();
        assertThat(reference.view(on(3, 31)).entity(id)).isPresent();
        assertThat(reference.view(on(4, 1)).entity(id)).isEmpty();
    }

    @Test
    void anUnknownEntityIsNotFound() {
        assertThat(reference.view(on(2, 1)).entity(id(1))).isEmpty();
        assertThat(reference.view(on(2, 1)).timeline(id(1))).isEmpty();
    }

    @Test
    void anEntityStateSaysWhichTransactionRecordedIt() {
        Receipt created = reference.createParties();
        EntityId acme = (EntityId) created.temporaryIds().get(ACME);
        Receipt patched = reference.recordedOn(on(3, 16)).commit(Transaction.by("importer")
                .patch(acme, from(3, 15), values("name", "ACME Holdings")).build());

        EntityState before = reference.view(on(3, 1)).entity(acme).orElseThrow();
        EntityState after = reference.view(on(3, 31)).entity(acme).orElseThrow();

        assertThat(before.recordedBasis()).isEqualTo(created.basis());
        assertThat(before.recordedAt()).isEqualTo(created.transactionTime());
        assertThat(after.recordedBasis()).isEqualTo(patched.basis());
        assertThat(after.recordedAt()).isEqualTo(patched.transactionTime());
        assertThat(after.period()).isEqualTo(EffectivePeriod.from(on(3, 15)));
        assertThat(before.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(3, 15)));
    }

    @Test
    void valuesAreInTheOrderOfTheAttributesInTheSchemaWhateverTheOrderOfTheWrite() {
        EntityId acme = (EntityId) reference.commit(Transaction.by("importer").create(ACME, "Organization",
                from(1, 1), values("lei", ReferenceStore.LEI, "name", "ACME")).build()).temporaryIds().get(ACME);

        EntityState state = reference.view(on(2, 1)).entity(acme).orElseThrow();

        assertThat(state.values().keySet()).containsExactly("name", "lei");
    }

    // ------------------------------------------------------------------ listing

    @Test
    void anUnknownEntityTypeCannotBeListed() {
        assertThatThrownBy(() -> reference.view(on(2, 1)).entities("Organisation"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Organisation");
        assertThatThrownBy(() -> reference.view(on(2, 1)).entities("Named"))
                .as("a trait is not an entity type").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntityTypeIsFoundWithoutRegardToCase() {
        reference.createParties();

        assertThat(reference.view(on(2, 1)).entities("oRGANIZATION")).hasSize(2);
    }

    @Test
    void listedEntitiesAreOrderedById() {
        reference.commit(Transaction.by("importer")
                .create(id(30), "Organization", from(1, 1), values("name", "Third"))
                .create(id(10), "Organization", from(1, 1), values("name", "First"))
                .create(id(20), "Person", from(1, 1), values("name", "Second")).build());

        assertThat(reference.view(on(2, 1)).entities("Party")).extracting(EntityState::id)
                .containsExactly(id(10), id(20), id(30));
    }

    @Test
    void aListingHoldsOnlyTheEntitiesThatExistAtTheEffectiveTime() {
        EntityId beta = (EntityId) reference.createParties().temporaryIds().get(BETA);
        reference.commit(Transaction.by("importer").endDate(beta, on(6, 1)).build());
        reference.commit(Transaction.by("importer").create(new TemporaryId("later"),
                "Organization", from(9, 1), values("name", "Later")).build());

        assertThat(reference.view(on(5, 31)).entities("Organization")).hasSize(2);
        assertThat(reference.view(on(6, 1)).entities("Organization")).hasSize(1);
        assertThat(reference.view(on(9, 1)).entities("Organization")).hasSize(2);
    }

    @Test
    void aTypeWithoutEntitiesHasAnEmptyListing() {
        reference.createParties();

        assertThat(reference.view(on(2, 1)).entities("Account")).isEmpty();
        assertThat(reference.view(on(2, 1)).entities("Person")).extracting(EntityState::entityType)
                .containsExactly("Person");
    }

    @Test
    void aListingIsAsOfTheBasisOfTheView() {
        Receipt created = reference.createParties();
        View before = reference.store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(created.basis()));
        reference.commit(Transaction.by("importer").create(new TemporaryId("c"),
                "Organization", from(1, 1), values("name", "C")).build());

        assertThat(before.entities("Organization")).hasSize(2);
        assertThat(reference.view(on(2, 1)).entities("Organization")).hasSize(3);
    }

    // ------------------------------------------------------------------ timelines

    @Test
    void aTimelineIgnoresTheEffectiveTimeOfTheView() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer").patch(acme, from(6, 1), values("name", "ACME Holdings")).build());

        assertThat(reference.view(on(2, 1)).timeline(acme)).isEqualTo(reference.view(on(12, 1)).timeline(acme));
        assertThat(reference.view(on(2025, 1, 1)).timeline(acme)).hasSize(2);
    }

    @Test
    void aTimelineIsAsOfTheBasisOfTheView() {
        Receipt created = reference.createParties();
        EntityId acme = (EntityId) created.temporaryIds().get(ACME);
        reference.commit(Transaction.by("importer").patch(acme, from(6, 1), values("name", "ACME Holdings")).build());

        View earlier = reference.store.view(Coordinates.effectiveAt(on(2, 1)).knownAt(created.basis()));

        assertThat(earlier.timeline(acme)).hasSize(1);
        assertThat(reference.view(on(2, 1)).timeline(acme)).hasSize(2);
    }

    @Test
    void anEndedEntityKeepsItsTimeline() {
        EntityId beta = (EntityId) reference.createParties().temporaryIds().get(BETA);
        reference.commit(Transaction.by("importer").endDate(beta, on(6, 1)).build());

        List<EntityState> timeline = reference.view(on(12, 1)).timeline(beta);

        assertThat(timeline).singleElement().satisfies(state ->
                assertThat(state.period()).isEqualTo(EffectivePeriod.between(on(1, 1), on(6, 1))));
        assertThat(reference.view(on(12, 1)).entity(beta)).isEmpty();
    }

    // ------------------------------------------------------------------ names of the view's schema

    @Test
    void aRenamedEntityTypeIsReportedUnderItsNewNameInNewViewsOnly() {
        EntityId acme = (EntityId) reference.createParties().temporaryIds().get(ACME);
        View old = reference.view(on(2, 1));
        SchemaDefinition model = ReferenceModel.definition();
        List<EntityTypeDefinition> types = model.entityTypes().stream().map(type -> type.name().equals("Address")
                ? new EntityTypeDefinition("Location", type.isAbstract(), type.supertype(), type.traits(),
                        type.attributes(), List.of("Address"), type.deprecated(), type.description(), type.rules())
                : type).toList();
        // HasAddress has Address as its target, so it names the new name of the entity type.
        SchemaDefinition renamed = new SchemaDefinition(types, model.traits(), model.relationshipTypes().stream()
                .map(type -> type.name().equals("HasAddress")
                        ? new RelationshipTypeDefinition(type.name(), type.source(),
                                "Location", type.targetsPerSource(), type.sourcesPerTarget(), type.inverseName(),
                                type.inverseAliases(), type.kind(), type.symmetric(), type.supertype(),
                                type.attributes(), type.aliases(), type.deprecated(), type.description(), type.rules())
                        : type).toList());
        assertThat(reference.store.schema().register(ReferenceModel.origin("1.1.0"), renamed))
                .isInstanceOf(RegistrationResult.Applied.class);
        reference.commit(Transaction.by("importer").create(new TemporaryId("home"),
                "Address", from(1, 1), values("street", "1 Main St", "city", "Springfield", "country", "US"))
                .build());

        assertThat(reference.view(on(2, 1)).entities("Location")).extracting(EntityState::entityType)
                .containsExactly("Location");
        assertThat(reference.view(on(2, 1)).entities("Address")).hasSize(1);
        assertThat(old.schema().entityType("Location")).isEmpty();
        assertThat(old.entity(acme)).isPresent();
    }

    @Test
    void aViewReportsAWidenedAttributeInTheTypeOfItsOwnSchema() {
        ReferenceStore gadgets = ReferenceStore.with(StoreWritesTest.gadgets());
        EntityId id = (EntityId) gadgets.commit(Transaction.by("tester").create(new TemporaryId("g"), "Gadget",
                from(1, 1), values("code", "G-1", "level", 3)).build()).temporaryIds().get(new TemporaryId("g"));
        View narrow = gadgets.view(on(2, 1));
        var widened = Definitions.changeEntityType(StoreWritesTest.gadgets(), "Gadget", gadget ->
                Definitions.changeAttribute(gadget, "level", old ->
                        AttributeDefinition.builder("level", ValueType.LONG).range(old.range().orElseThrow())
                                .build()));
        gadgets.clock.advance(Duration.ofMinutes(1));
        assertThat(gadgets.store.schema().register(ReferenceModel.origin("1.1.0"), widened))
                .isInstanceOf(RegistrationResult.Applied.class);

        View wide = gadgets.view(on(2, 1));

        assertThat(narrow.entity(id).orElseThrow().values()).containsEntry("level", Value.of(3));
        assertThat(wide.entity(id).orElseThrow().values()).containsEntry("level", Value.of(3L));
        assertThat(wide.timeline(id)).singleElement().satisfies(state ->
                assertThat(state.values()).containsEntry("level", Value.of(3L)));
        assertThat(wide.entities("Gadget")).singleElement().satisfies(state ->
                assertThat(state.values()).containsEntry("level", Value.of(3L)));
    }
}
