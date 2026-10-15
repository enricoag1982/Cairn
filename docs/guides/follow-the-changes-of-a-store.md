---
last-reviewed: 2026-10-15
owner: ""
---

# Follow the changes of a store

How an application or an agent learns what changed in a store, to notify people, update a search index or copy data elsewhere.
Side effects never run inside the engine ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)); they run in applications that follow the *change feed*.
Why the feed works this way is in [ADR-0029](../decisions/0029-feed-changes-from-an-index-of-what-each-basis-changed-read-from-a-basis.md); what it guarantees is in the `change-feed` spec.

## What a change says

Every basis of a store has one change, in basis order, without gaps:

| Change | When | What it holds |
| --- | --- | --- |
| `TransactionChange` | A data transaction committed | Its `TransactionRecord`, and `changed`: every entity and relationship it changed |
| `SchemaChange` | A schema registration was applied | Its `SchemaTransaction` |
| `PurgeChange` | A purge ran | Its `PurgeRecord` |

Each `Changed` entry of a transaction names an entity or relationship by its ID, with its type, whether the transaction created it, and the effective periods in which its versions differ from before the transaction, being absent included.
A correction of A-1's balance from 15 March changes A-1 from 15 March on, not before. A write that leaves the values as they were records no new version, so it is no change; a transaction that changes nothing still has its change, with its record and nothing in `changed`.
Dry runs and rejected transactions leave no change.

A change carries no values. Read them through views at the change's basis, and at the basis before for what was there:

```java
if (change instanceof TransactionChange transaction) {
    for (Changed changed : transaction.changed()) {
        EffectivePeriod period = changed.periods().getFirst();
        View after = store.view(Coordinates.effectiveAt(period.from()).knownAt(change.basis()));
        View before = store.view(Coordinates.effectiveAt(period.from())
                .knownAt(new Basis(change.basis().value() - 1)));
        // compare after.entity(...) with before.entity(...), or read after.timeline(...)
    }
}
```

## Read from a basis

```java
Basis cursor = loadCursor();                          // Basis.NONE the first time
List<Change> changes = store.changes(cursor, 100);    // at most 100, in basis order
for (Change change : changes) {
    handle(change);
    saveCursor(change.basis());                       // after handling, so a crash repeats rather than loses
}
```

Keep the last basis you handled where your application keeps its own state, and read from it again after a restart. The store keeps no position per consumer, and every change stays readable for as long as the store keeps its history.
Handle a change so that handling it twice does no harm: a crash between handling and saving the cursor repeats it.

## Subscribe

`changeFeed(after)` returns a `java.util.concurrent.Flow.Publisher<Change>`, which libraries such as Reactor and RxJava adapt.

```java
store.changeFeed(cursor).subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;
    public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
    public void onNext(Change change) { handle(change); saveCursor(change.basis()); subscription.request(1); }
    public void onError(Throwable failure) { /* log, and subscribe again from the saved cursor */ }
    public void onComplete() { /* the store was closed */ }
});
```

- A subscription delivers each change once, in basis order, on a thread of its own, never the committing one, and only as many as you request. It reads the store at your pace, so a slow consumer never slows commits.
- When it has delivered everything, it waits for the next commit.
- If `onNext` throws, the subscription is cancelled; subscribe again from your saved cursor.
- Closing a durable or RocksDB store completes its subscriptions. An in-memory store never completes them.

## Purges

A purge removes what it purges from the changes too: a change read after the purge no longer names a purged entity or its relationships, and a transaction that only touched them keeps its record with nothing in `changed`.
The purge itself is a `PurgeChange`, with its record, which says how much was removed but not what ([ADR-0024](../decisions/0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md)).
Changes you read before the purge stay with you: when you see a `PurgeChange`, check the IDs you keep with `store.wasPurged(id)` and erase what was purged.

## Agents

Agents read the feed with the tool `cairn_list_changes`, which takes a basis and a limit and returns the changes as JSON with the basis to continue from; see its row in [Serve a store to AI agents](serve-a-store-to-ai-agents.md#the-tools).
An agent that polls keeps `next` between calls.

## Limits

- A fact recorded with a future effective date causes a change when it is recorded, not when it becomes effective ([ADR-0008](../decisions/0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)).
- The feed cannot be filtered by type yet; skip the entries you do not need.
- Type names are those at the change's basis; after a rename, follow the schema history.
