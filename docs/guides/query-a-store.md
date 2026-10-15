---
last-reviewed: 2026-10-13
owner: ""
---

# Query a store

How to filter entities, follow relationships, and total values with *queries*, in Java or as JSON.
Why queries look like this is in [ADR-0023](../decisions/0023-express-queries-as-data-a-pipeline-of-steps-over-named-bindings-with-a-java-builder-and-a-json-form.md); what each part means is in the `queries` spec.

## How a query works

A query is data that a *view* runs. It starts from an entity type and gives each entity a name, its *binding*. Then each *query step* works on the rows so far:

| Step | What it does to each row |
| --- | --- |
| `where` | Keeps the row if a condition holds |
| `follow` | Gives one row per relationship that leads from a binding by a relationship name or inverse name, binding the entity at the other end, and if asked the relationship |
| `optionalFollow` | The same, but keeps a row without such relationships, with empty bindings |
| `traverse` | Gives one row per entity reached by a name at depths from a minimum to a maximum, optionally only along relationships that meet a condition (`along`); depth 0 is the binding itself |

After the steps, a query can group rows and compute aggregates (count, sum, minimum, maximum, average), name the columns to return, order the rows and limit them.
Everything is read at the view's coordinates: one effective time, as known at one basis or transaction time.

## In Java

The total balance of accounts by ultimate parent, story RD-6 of the [reference domain](../reference/reference-domain.md), where a party's ultimate parent is found by following `ownedBy` while the share is above 50%:

```java
Condition overHalf = Condition.gt(Operand.attribute("ownership", "share"), Operand.literal(50));
Query query = Query.from("Account", "a")
        .follow("a", "HeldBy", "holder")                        // the party that holds the account
        .traverse("holder", "ownedBy", 0, 10, "party",          // the holder and its owners, up to 10 levels,
                "ownership", overHalf)                          // along ownerships above 50%
        .where(Condition.not(Condition.hasLink("party", "ownedBy", "large",     // keep those without
                Condition.gt(Operand.attribute("large", "share"), Operand.literal(50)))))   // such an owner
        .groupBy("party")
        .aggregate(Aggregate.sum(Operand.attribute("a", "balance"), "total"))
        .build();

QueryResult result = store.view(Coordinates.effectiveAt(march31)).query(query);
switch (result) {
    case QueryResult.Rows rows -> rows.rows().forEach(row ->
            System.out.println(row.entity("party").orElseThrow().values().get("name") + ": " + row.value("total").orElseThrow()));
    case QueryResult.Invalid invalid -> invalid.problems().forEach(System.out::println);
}
```

A query for a type includes its subtypes; `exactType()` after `from` leaves them out.
Without `select`, a query returns its bindings, or its group keys and aggregates when it groups. `orderBy` and `limit` come last.

With generated types ([guide](generate-java-types.md)), each attribute has a typed reference, so values are checked by the compiler:

```java
Query rich = Query.from(Account.TYPE, "a")
        .where(Account.BALANCE.of("a").gt(new BigDecimal("1200")))
        .build();
List<Account> accounts = ((QueryResult.Rows) view.query(rich)).rows().stream()
        .map(row -> Account.from(row.entity("a").orElseThrow()))
        .toList();
```

## As JSON

The same RD-6 query, as agents send it to `cairn_query` and as `Queries.read` in `cairn-documents` reads it (YAML 1.2 works too):

```json
{
  "from": { "type": "Account", "as": "a" },
  "steps": [
    { "follow": { "from": "a", "name": "HeldBy", "as": "holder" } },
    { "traverse": { "from": "holder", "name": "ownedBy", "minDepth": 0, "maxDepth": 10, "as": "party",
                    "relationship": "ownership",
                    "along": { "compare": { "left": { "attribute": "share", "of": "ownership" }, "op": "gt", "right": { "value": 50 } } } } },
    { "where": { "not": { "hasLink": { "of": "party", "name": "ownedBy", "relationship": "large",
                    "along": { "compare": { "left": { "attribute": "share", "of": "large" }, "op": "gt", "right": { "value": 50 } } } } } } }
  ],
  "groupBy": [ { "of": "party" } ],
  "aggregates": [ { "function": "sum", "attribute": "balance", "of": "a", "as": "total" } ]
}
```

Conditions compare operands, such as `{ "compare": { "left": { "attribute": "balance", "of": "a" }, "op": "gt", "right": { "value": 1200 } } }`, with `op` one of `eq`, `ne`, `lt`, `le`, `gt` and `ge`.
The JSON Schema of the form is published with `cairn-documents` (`Queries.jsonSchema()`), and `cairn_json_schema` returns it for the kind `query`.
`Queries.toJson` writes a result as `{ effectiveAt, basis, columns, rows }`, each row a list of cells in the JSON result forms of the `json-payloads` spec (entity and relationship states, values), or `null` for an empty cell; `cairn_query` returns `{ effectiveAt, basis, result: { columns, rows } }`, like the other reads.

## What to expect

- **Missing values never match a comparison.** `balance > 0` is false for an account without a balance; test for a value with `hasValue` and `not`.
- **Numbers compare by value** whatever their value types; strings by code point; dates and instants in time. A string compared with a date or instant attribute is read as one if it spells one, in the forms of payloads; otherwise the query has a problem.
- **Rows multiply along relationships.** A traversal without a condition reaches every owner, so a party with two owners that nobody owns counts in both of their groups. A condition on the relationships followed, such as RD-6's share above 50, keeps one chain where the data has one.
- **A traversal stops at its maximum depth.** A chain longer than that is cut, so the last party reached may still have an owner; choose the depth with the data in mind.
- **The order is stable.** Rows come in the order you ask for, then by the IDs of their bindings, so the same query on the same data gives the same rows in the same order, whatever stores them.
- **Mistakes come back as problems**, before anything is read: an unknown type, binding, attribute or relationship name, with the closest valid name as a hint, or values that cannot be compared or summed.

## Limits

Queries scan the entities of their start type at the view's coordinates; there are no indexes yet. Each query reads one view: a value's history over time is read with timelines, not queries. There is no text query language yet.
