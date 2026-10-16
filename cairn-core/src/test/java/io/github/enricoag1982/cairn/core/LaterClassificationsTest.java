package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.onlyProblem;
import static io.github.enricoag1982.cairn.core.ReferenceStore.entity;
import static io.github.enricoag1982.cairn.fixtures.Definitions.withClassification;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.A1;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.ACME;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.BETA;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.LEI;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.money;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.Clearance;
import io.github.enricoag1982.cairn.data.Coordinates;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Link;
import io.github.enricoag1982.cairn.data.Receipt;
import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.data.WithheldKeyException;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Column;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Order;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A restricted view withholds an attribute unless its clearance covers the attribute's classification both in the view's
 * schema and in the store's latest schema, matched by the attribute's identifier (ADR-0036, design D2 of
 * {@code add-attribute-access-control}): so a view at an old basis does not show what has been classified since, the query
 * check and the check of a unique key follow the same rule, a rename does not shake the classification off, and a
 * classification that was removed still applies to the views of the time that it held. The tests run against RocksDB stores
 * too.
 */
class LaterClassificationsTest {

    private static final Clearance NONE = Clearance.none();

    private static SchemaDefinition finance() {
        return withClassification(ReferenceModel.definition(), "Account", "balance", "finance");
    }

    private static void register(ReferenceStore reference, String version, SchemaDefinition definition) {
        reference.recordedOn(on(6, 1));
        assertThat(reference.store.schema().register(ReferenceModel.origin(version), definition))
                .isInstanceOf(RegistrationResult.Applied.class);
    }

    /** A view at the basis of a receipt, effective on 1 April. */
    private static View viewAt(ReferenceStore reference, Receipt receipt) {
        return reference.store.view(Coordinates.effectiveAt(on(4, 1)).knownAt(receipt.basis()));
    }

    // ------------------------------------------------------------------ restricting

    @Test
    void everyStateAViewFromBeforeAClassificationReturnsListsWhatIsClassifiedNowAsWithheld() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        EntityId beta = entity(reference.parties, BETA);
        register(reference, "1.1.0", finance());
        View restricted = viewAt(reference, accounts).restrictedTo(NONE);

        EntityState read = restricted.entity(a1).orElseThrow();
        List<EntityState> listed = restricted.entities("Account");
        List<EntityState> timeline = restricted.timeline(a1);
        List<Link> navigated = restricted.navigate(beta, "holds");
        QueryResult.Rows rows = (QueryResult.Rows) restricted.query(Query.from("Account", "a").build());

        assertThat(read.withheld()).containsExactly("balance");
        assertThat(read.values()).doesNotContainKey("balance");
        assertThat(listed).hasSize(2).allSatisfy(state -> assertThat(state.withheld()).containsExactly("balance"));
        assertThat(timeline).isNotEmpty().allSatisfy(state -> assertThat(state.values()).doesNotContainKey("balance"));
        assertThat(navigated).singleElement().satisfies(link ->
                assertThat(link.entity().withheld()).containsExactly("balance"));
        assertThat(rows.rows()).hasSize(2).allSatisfy(row ->
                assertThat(row.entity("a").orElseThrow().withheld()).containsExactly("balance"));
    }

    @Test
    void aViewMadeBeforeTheClassificationIsRegisteredWithholdsWhenItIsRead() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        View plain = reference.view(on(4, 1));
        View restrictedBefore = plain.restrictedTo(NONE);
        assertThat(restrictedBefore.entity(a1).orElseThrow().values()).containsKey("balance");

        register(reference, "1.1.0", finance());

        assertThat(restrictedBefore.entity(a1).orElseThrow().withheld()).containsExactly("balance");
        assertThat(plain.restrictedTo(NONE).entity(a1).orElseThrow().withheld()).containsExactly("balance");
        assertThat(plain.entity(a1).orElseThrow().values()).as("a view that is not restricted").containsKey("balance");
    }

    @Test
    void aClearanceThatCoversTheClassificationReadsAtAnyBasis() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        register(reference, "1.1.0", finance());

        EntityState state = viewAt(reference, accounts).restrictedTo(Clearance.of("finance")).entity(a1).orElseThrow();

        assertThat(state.withheld()).isEmpty();
        assertThat(state.values()).containsEntry("balance", Value.of(money("1000")));
    }

    @Test
    void anAttributeWithoutAClassificationInEitherSchemaIsNeverWithheld() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        register(reference, "1.1.0", withClassification(ReferenceModel.definition(), "Organization", "lei", "identity"));

        EntityState state = viewAt(reference, accounts).restrictedTo(NONE).entity(a1).orElseThrow();

        assertThat(state.withheld()).isEmpty();
        assertThat(state.values()).containsKeys("number", "currency", "balance");
    }

    // ------------------------------------------------------------------ a classification that was changed

    @Test
    void aRenameKeepsTheAttributeWithheldByItsIdentifier() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        register(reference, "1.1.0", finance());
        register(reference, "1.2.0", Definitions.changeEntityType(finance(), "Account", account ->
                Definitions.changeAttribute(account, "balance", balance ->
                        Definitions.renamed(balance, "amount", "balance"))));

        EntityState state = viewAt(reference, accounts).restrictedTo(NONE).entity(a1).orElseThrow();

        assertThat(state.withheld()).as("the old view still calls it balance").containsExactly("balance");
        assertThat(state.values()).doesNotContainKey("balance");
    }

    @Test
    void aClassificationThatWasRemovedSinceStillAppliesToTheViewsOfTheTimeItHeld() {
        ReferenceStore reference = ReferenceStore.with(finance());
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        register(reference, "1.1.0", ReferenceModel.definition());

        EntityState old = viewAt(reference, accounts).restrictedTo(NONE).entity(a1).orElseThrow();
        EntityState latest = reference.view(on(4, 1)).restrictedTo(NONE).entity(a1).orElseThrow();

        assertThat(old.withheld()).containsExactly("balance");
        assertThat(latest.withheld()).as("the latest schema has no classification").isEmpty();
        assertThat(latest.values()).containsKey("balance");
    }

    @Test
    void anAttributeClassifiedDifferentlyNeedsBothClassificationsCovered() {
        ReferenceStore reference = ReferenceStore.with(finance());
        Receipt accounts = reference.createAccounts();
        EntityId a1 = entity(accounts, A1);
        register(reference, "1.1.0", withClassification(ReferenceModel.definition(), "Account", "balance", "treasury"));
        View old = viewAt(reference, accounts);

        assertThat(old.restrictedTo(Clearance.of("finance")).entity(a1).orElseThrow().withheld())
                .as("finance covers the classification of the view, not the one of the store now")
                .containsExactly("balance");
        assertThat(old.restrictedTo(Clearance.of("treasury")).entity(a1).orElseThrow().withheld())
                .as("treasury covers the classification of the store now, not the one of the view")
                .containsExactly("balance");
        assertThat(old.restrictedTo(Clearance.of("finance", "treasury")).entity(a1).orElseThrow().withheld())
                .isEmpty();
    }

    // ------------------------------------------------------------------ queries

    @Test
    void aQueryOverAnAttributeClassifiedSinceIsRefusedWhateverTheBasisOfTheView() {
        ReferenceStore reference = new ReferenceStore();
        Receipt accounts = reference.createAccounts();
        register(reference, "1.1.0", finance());
        View restricted = viewAt(reference, accounts).restrictedTo(NONE);
        Query filter = Query.from("Account", "a")
                .where(Condition.gt(Operand.attribute("a", "balance"), Operand.literal(money("1000")))).build();
        Query sum = Query.from("Account", "a").aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
                .build();
        Query order = Query.from("Account", "a").orderBy(Order.ascending(
                Column.attribute("a", "balance"))).build();

        Problem filtered = onlyProblem(restricted.query(filter));
        Problem summed = onlyProblem(restricted.query(sum));
        Problem ordered = onlyProblem(restricted.query(order));

        assertThat(filtered.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(filtered.path()).isEqualTo("@query.steps.0");
        assertThat(filtered.message()).contains("classified finance");
        assertThat(summed.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(summed.path()).isEqualTo("@query.aggregates.0");
        assertThat(ordered.code()).isEqualTo(ProblemCode.ACCESS_DENIED);
        assertThat(viewAt(reference, accounts).restrictedTo(Clearance.of("finance")).query(filter))
                .isInstanceOf(QueryResult.Rows.class);
        assertThat(viewAt(reference, accounts).query(filter)).as("a view that is not restricted")
                .isInstanceOf(QueryResult.Rows.class);
    }

    // ------------------------------------------------------------------ keys

    @Test
    void aKeyOfAnAttributeClassifiedSinceIsRefusedWhetherOrNotAnEntityHasTheValue() {
        ReferenceStore reference = new ReferenceStore();
        Receipt parties = reference.createParties();
        EntityId acme = entity(parties, ACME);
        Receipt withLei = reference.replay(ReferenceStories.rd9AcmeLei(acme));
        register(reference, "1.1.0", withClassification(ReferenceModel.definition(), "Organization", "lei", "identity"));
        View old = reference.store.view(Coordinates.effectiveAt(on(4, 1)).knownAt(withLei.basis()));
        UniqueKey held = new UniqueKey("Organization", "lei", Value.of(LEI));
        UniqueKey nobodys = new UniqueKey("Organization", "lei", Value.of("5493001KJTIIGC8Y1R13"));

        assertThat(old.entity(held)).as("a view that is not restricted finds ACME").map(EntityState::id).hasValue(acme);
        for (UniqueKey key : List.of(held, nobodys)) {
            assertThatThrownBy(() -> old.restrictedTo(NONE).entity(key)).isInstanceOf(WithheldKeyException.class)
                    .hasMessageContaining("lei").hasMessageContaining("identity")
                    .satisfies(thrown -> {
                        WithheldKeyException withheld = (WithheldKeyException) thrown;
                        assertThat(withheld.entityType()).isEqualTo("Organization");
                        assertThat(withheld.attribute()).isEqualTo("lei");
                        assertThat(withheld.classification()).isEqualTo("identity");
                    });
        }
        assertThat(old.restrictedTo(Clearance.of("identity")).entity(held)).map(EntityState::id).hasValue(acme);
    }
}
