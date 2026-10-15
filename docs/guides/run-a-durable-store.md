---
last-reviewed: 2026-10-15
owner: ""
---

# Run a durable store

How to keep a Cairn store on disk, so that its data survives a restart or a crash.
A *durable store* is a store in memory that writes every committed transaction to a *commit log* in a directory, and recovers from that directory when it is opened again.
Why it works this way is in [ADR-0022](../decisions/0022-make-stores-durable-with-a-commit-log-of-effects-and-snapshots.md); what it guarantees is in the `durability` spec.

## Open and close

```java
StoreSettings settings = StoreSettings.builder()
        .rules(new LeiChecksum())
        .build();
try (DurableStore store = Stores.durable(Path.of("/var/lib/my-app/cairn"), settings)) {
    store.schema().register(origin, definition);
    store.commit(transaction);
    // ... the application runs ...
}
```

`Stores.durable` creates the directory if it does not exist, and otherwise recovers the store that is in it.
Closing writes a *snapshot file* and releases the directory. A closed store refuses every call but `close`, which does nothing then.
One open store holds a directory at a time, in any process: opening a second one on the same directory throws `IllegalStateException`.

The settings are those of an in-memory store. Keep an ID generator of the same format: opening a store with a generator that does not accept the IDs it holds fails ([ADR-0019](../decisions/0019-identify-entities-by-opaque-ids-from-one-generator-per-store.md)). A store whose schema declares *checks* needs the module `cairn-cel` when it is opened, or it rejects writes to the types they apply to ([Write checks](write-checks.md)). A store can be opened with other rules than it was written with: recovery does not run rules, and a rule that the schema names but the store has no code for makes writes to the affected types fail, as for any store ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)).
After recovery, the store runs each rule it has code for once over the data, without judging the results, to learn what the rules read, so that a later change to that data runs them again ([ADR-0025](../decisions/0025-run-a-rule-again-when-the-data-it-read-changes.md)). A store without a rule's code cannot learn what it reads, so writes to other types can break that rule unnoticed. Data that breaks a rule, for example because the rule's code changed, is not reported when the store opens; it rejects the later writes that change what the rule read for it, until it is corrected.

## What a commit guarantees

A commit returns only after its transaction is written and forced to disk. A transaction that returned committed survives a crash of the process or the machine; one that did not may be lost, but never half applied.
Dry runs and rejected transactions write nothing.

If writing to disk fails, for example because the disk is full, the commit throws `UncheckedIOException` and is not applied. The store then refuses further commits and schema registrations until it is opened again; reads go on.
If the record was written whole and only forcing it to disk failed, it may still be on disk, and the transaction is then recovered when the store is opened again. Give transactions that you retry after a failure an idempotency key, so that a retry does not record them twice.

Forcing every commit to disk costs time: a durable store commits as fast as its disk syncs.

## Snapshot files

Recovery reads the newest snapshot file and then the log after it, so the longer the log since the last snapshot, the longer opening takes. Running the rules once afterwards takes time in proportion to the data their types hold.
The store writes a snapshot file when it is closed. A long-running application should also call `writeSnapshot()` now and then, for example once an hour or after every few thousand commits; commits go on while it writes.

The store keeps its two newest snapshot files and the log needed to recover from the older one, and deletes the rest. If the newest snapshot file is damaged, recovery uses the older one. Right after a purge there is only one (see [Purges](#purges)).

## The directory

```text
lock                               held while a store is open
log/00000000000000000001.log       log segments, named by their first basis
snapshots/00000000000000000042.snapshot
```

Do not change the files. To back a store up, close it and copy the directory. Copying the files of an open store is not supported yet.

## Purges

`store.purge(Purge.by("dpo").reason("Ticket DPO-1234").entity(aliceId).build())` removes Alice from all of history ([ADR-0024](../decisions/0024-purge-entities-by-removing-them-from-all-of-history-and-record-that-a-purge-happened.md), the `purge` spec): every version of her at every basis, her relationships, and the targets of her compositions that only she composed.
A durable store writes the purge to its log, then a snapshot file without the purged data, and deletes every older snapshot file and log segment before `purge` returns, so no file of the store holds the data any more. Commits wait while it rewrites the files.
The store lists its purge records with `purges()`, which say how much was removed but not what; `wasPurged(id)` tells whether an ID was purged.

- **Who and why stay.** Purge records and the records of earlier transactions keep their principals, parties acted for and reasons. Put a ticket reference there, never the identity of the person whose data you erase.
- **Views across a purge.** A view that reads while a purge runs may see part of it, and every view reads the purged state afterwards. Do not rely on results read before a purge.
- **If the store stops during a purge** after the purge reached its log, opening the store completes the purge. If writing the snapshot file or deleting fails, `purge` throws `UncheckedIOException`; the purge is applied in memory, the store refuses commits, and opening it again completes the purge.
- **One snapshot file until the next.** A purge leaves one snapshot file. If it is damaged, the store refuses to open rather than open without the data the deleted files held, so take a backup after a purge.
- **Retries stay refused.** The store drops the transactions it kept for idempotency keys that wrote purged data or named a purged entity, by ID or by unique key, from memory and its files; their keys stay used, so a retry is rejected with `IDEMPOTENCY_KEY_PURGED`.
- **Copies elsewhere** keep the data: backups, changes already read from the [change feed](follow-the-changes-of-a-store.md), approval requests that agents submitted, and data read before. Purge them yourself.

## After a crash

Opening the directory recovers every committed transaction.
A crash during a commit can leave the last record of the log incomplete, or partly zero bytes; recovery drops it, and that transaction was never committed. The price is that damage to only the last record, after its commit returned, looks the same and is dropped too.
Damage anywhere else in the log stops the store from opening with an `IllegalStateException` that names the file and the position; a damaged snapshot file is skipped, and recovery uses the older one. Files written in a format version this version of Cairn does not know stop the store from opening with an exception that names the file and the version. Nothing is changed on disk then, so the files can be inspected or restored from a backup.

## Limits

- All data is held in memory as well. A durable store suits data that fits in the heap; for more, [keep the store in RocksDB](keep-a-store-in-rocksdb.md).
- The log and snapshot files keep each entity's whole timeline at every basis, so entities with very many versions make them large.
