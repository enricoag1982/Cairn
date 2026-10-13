---
last-reviewed: 2026-10-13
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
Closing writes a *snapshot file* and releases the directory. A closed store refuses every call.
One open store holds a directory at a time, in any process: opening a second one on the same directory throws `IllegalStateException`.

The settings are those of an in-memory store. A store can be opened with other rules than it was written with: recovery does not run rules, and a rule that the schema names but the store has no code for makes writes to the affected types fail, as for any store ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)).

## What a commit guarantees

A commit returns only after its transaction is written and forced to disk. A transaction that returned committed survives a crash of the process or the machine; one that did not may be lost, but never half applied.
Dry runs and rejected transactions write nothing.

If writing to disk fails, for example because the disk is full, the commit throws `UncheckedIOException` and is not applied. The store then refuses further commits and schema registrations until it is opened again; reads go on.

Forcing every commit to disk costs time: a durable store commits as fast as its disk syncs.

## Snapshot files

Recovery reads the newest snapshot file and then the log after it, so the longer the log since the last snapshot, the longer opening takes.
The store writes a snapshot file when it is closed. A long-running application should also call `writeSnapshot()` now and then, for example once an hour or after every few thousand commits; commits go on while it writes.

The store keeps its two newest snapshot files and the log needed to recover from the older one, and deletes the rest. If the newest snapshot file is damaged, recovery uses the older one.

## The directory

```text
lock                               held while a store is open
log/00000000000000000001.log       log segments, named by their first basis
snapshots/00000000000000000042.snapshot
```

Do not change the files. To back a store up, close it and copy the directory. Copying the files of an open store is not supported yet.

## After a crash

Opening the directory recovers every committed transaction.
A crash during a commit can leave the last record of the log incomplete; recovery drops it, and that transaction was never committed.
Damage anywhere else, or files written in a format version this version of Cairn does not know, stop the store from opening with an `IllegalStateException` that names the file and the position. Nothing is changed on disk then, so the files can be inspected or restored from a backup.

## Limits

- All data is held in memory as well. A durable store suits data that fits in the heap; a key-value store comes later ([ADR-0011](../decisions/0011-hide-storage-behind-an-ordered-key-value-store-and-a-commit-log.md)).
- The log and snapshot files keep each entity's whole timeline at every basis, so entities with very many versions make them large.
- Purging (RD-11 in the [reference domain](../reference/reference-domain.md)) is not built yet.
