package io.github.enricoag1982.cairn.fixtures;

import io.github.enricoag1982.cairn.query.Aggregate;
import io.github.enricoag1982.cairn.query.Condition;
import io.github.enricoag1982.cairn.query.Operand;
import io.github.enricoag1982.cairn.query.Query;

/**
 * The queries of the stories of the reference domain ({@code docs/reference/reference-domain.md}) that are queries: RD-6,
 * the total balance by ultimate parent, and RD-10, the listing of parties and of organizations. They are built with the
 * builder of {@link Query}; tests of the other forms of queries, such as the JSON form and typed references, compare
 * their rows with those of these. Run them against a store in which the stories RD-1, RD-2 and RD-5 were replayed (see
 * {@link ReferenceStories}).
 *
 * <p>RD-11, the purge, is neither a transaction nor a query: {@link ReferenceStories#rd11PurgeAlice} builds it.
 */
public final class ReferenceQueries {

    private ReferenceQueries() {
    }

    /**
     * RD-6: the total balance of accounts by ultimate parent. It follows {@code HeldBy} from an account to its holder,
     * traverses {@code ownedBy} from the holder from depth 0 up to depth 10 along only the ownerships whose share is
     * above 50, as the reference domain defines the ultimate parent, keeps the reached parties that have no
     * {@code ownedBy} link whose share is above 50, groups by them and sums the balances of the accounts. Effective 31
     * March, as known on 5 April, it has the row ACME with 3,500, and as known now ACME with 3,200.
     *
     * <p>The bindings are {@code a} (the account), {@code holder}, {@code party} and {@code ownership} (the relationship
     * that the traversal followed, which its condition reads); the columns are {@code party}, the state of the
     * ultimate parent, and {@code total}, a decimal. The name {@code owns} in the last filter belongs to that condition
     * alone: it is how the condition on the links of a party refers to their relationships, and no binding of the
     * query. A party whose owners all hold 50% or less has no such link, so it is its own ultimate parent.
     *
     * @return the query
     */
    public static Query totalBalanceByUltimateParent() {
        return Query.from("Account", "a")
                .follow("a", "HeldBy", "holder")
                .traverse("holder", "ownedBy", 0, 10, "party", "ownership",
                        Condition.gt(Operand.attribute("ownership", "share"), Operand.literal(50)))
                .where(Condition.not(Condition.hasLink("party", "ownedBy", "owns",
                        Condition.gt(Operand.attribute("owns", "share"), Operand.literal(50)))))
                .groupBy("party")
                .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
                .build();
    }

    /**
     * RD-10, the first query: the parties, with those of their subtypes. Effective 31 March it returns ACME, Beta and
     * Alice. The binding, and the only column, is {@code p}.
     *
     * @return the query
     */
    public static Query parties() {
        return Query.from("Party", "p").build();
    }

    /**
     * RD-10, the second query: the organizations. Effective 31 March it returns ACME and Beta. The binding, and the only
     * column, is {@code o}.
     *
     * @return the query
     */
    public static Query organizations() {
        return Query.from("Organization", "o").build();
    }
}
