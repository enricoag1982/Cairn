package io.github.enricoag1982.cairn.core;

import static io.github.enricoag1982.cairn.core.QueryRows.names;
import static io.github.enricoag1982.cairn.core.QueryRows.problems;
import static io.github.enricoag1982.cairn.core.QueryRows.rows;
import static io.github.enricoag1982.cairn.core.ReferenceStore.id;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.from;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.on;
import static io.github.enricoag1982.cairn.fixtures.ReferenceStories.values;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.data.View;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Query;
import io.github.enricoag1982.cairn.query.QueryResult;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Value;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every view runs queries (design D3 of {@code add-queries-and-aggregation}): the views of a store, and the view that a
 * rule reads through, which shows the state that the transaction would produce.
 */
class QueryViewsTest {

    private static final TemporaryId DELTA = new TemporaryId("delta");

    private static ReferenceStore storeWith(Rule rule) {
        List<Rule> rules = new ArrayList<>(ReferenceModel.executableRules());
        rules.add(rule);
        return ReferenceStore.withRules(
                TestEntityRule.namedIn(ReferenceModel.definition(), rule.descriptor().appliesTo(), rule.descriptor()),
                rules, ReferenceModel.declaredRules());
    }

    private static String name(EntityState state) {
        return ((Value.StringValue) state.values().get("name")).value();
    }

    @Test
    void aRuleQueriesTheStateTheTransactionWouldProduce() {
        List<QueryResult> seen = new ArrayList<>();
        TestEntityRule rule = TestEntityRule.of("query-probe", "Organization", (version, context) -> {
            if (name(version).equals("Delta")) {
                seen.add(context.view().query(Query.from("Party", "p").build()));
                seen.add(context.viewAt(context.view().effectiveAt().plusSeconds(1))
                        .query(Query.from("Party", "p").where(Condition.hasValue("p", "nickname")).build()));
            }
        });
        ReferenceStore reference = storeWith(rule);
        reference.createParties();

        reference.commit(Transaction.by("importer")
                .create(DELTA, "Organization", from(1, 1), values("name", "Delta"))
                .patch(id(2), from(1, 1), values("name", "Beta Two")).build());

        assertThat(names(rows(seen.get(0)), "p")).as("committed, changed and new entities, by ID")
                .containsExactly("ACME", "Beta Two", "Alice", "Delta");
        assertThat(problems(seen.get(1))).singleElement()
                .satisfies(problem -> assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_ATTRIBUTE));
        assertThat(rows(seen.get(0)).basis()).as("the basis the transaction would get")
                .isEqualTo(reference.store.latestBasis());
        assertThat(names(rows(reference.view(on(1, 1)).query(Query.from("Party", "p").build())), "p"))
                .containsExactly("ACME", "Beta Two", "Alice", "Delta");
    }

    @Test
    void aQueryOnAStoreViewDoesNotWaitForOrSeeLaterCommits() {
        ReferenceStore reference = new ReferenceStore();
        reference.createParties();
        View view = reference.view(on(1, 1));
        QueryResult before = view.query(Query.from("Party", "p").build());

        reference.commit(Transaction.by("importer").create(DELTA, "Organization", from(1, 1), values("name", "Delta"))
                .build());

        assertThat(view.query(Query.from("Party", "p").build())).isEqualTo(before);
        assertThat(names(rows(reference.view(on(1, 1)).query(Query.from("Party", "p").build())), "p"))
                .contains("Delta");
    }
}
