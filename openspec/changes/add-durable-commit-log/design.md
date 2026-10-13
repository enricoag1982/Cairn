# Design

## Context

ADR-0011 puts durability in a commit log, and ADR-0022 decides its content: the effects of each commit, framed with checksums, snapshot files, recovery without validation, one store per directory. This design settles the API, the files, recovery and the tests. The requirements are in `specs/durability/spec.md`.

The in-memory store already separates preparing a transaction from recording it: `InMemoryStore.record` appends the new timelines of the changed entities and relationships, adds the transaction record, keeps the idempotency key, and publishes the basis. A durable store writes the same effects to disk between preparing and recording.

## Goals / Non-Goals

**Goals:**

- One store implementation with an optional log, so that durable and in-memory stores cannot drift apart.
- Recovery that is exact, tested against an in-memory store that committed the same transactions.
- No new dependencies in `cairn-core`.

**Non-Goals:**

- Grouping commits, automatic periodic snapshots, change feeds, replicas, a storage SPI for other backends, inspection tools.

## Decisions

### D1. API

- `cairn-api`, package `data`: `DurableStore extends Store, AutoCloseable`, with `Basis writeSnapshot()` (writes a snapshot file of the latest basis and returns it) and `void close()` (writes a snapshot file, then releases the directory; calling it again does nothing).
- `cairn-core`: `Stores.durable(Path directory)` and `Stores.durable(Path directory, StoreSettings settings)`, which create the directory if needed, take its lock, and recover.
- Failures to open throw: `UncheckedIOException` for input and output errors, and `IllegalStateException` for a directory that another store holds, a damaged log, or an unknown format version, with a message naming the file and, for damage, the byte position.
- After `close`, every call but `close` throws `IllegalStateException`. After a failed write (D5), `commit` and `schema().register` throw `IllegalStateException` until the store is opened again; reads and dry runs go on.

### D2. Files

- `<directory>/lock`: held with a `FileChannel` lock while the store is open.
- `<directory>/log/<first basis, 20 digits>.log`: log segments. A new segment starts when the current one passes 64 MiB, and at every opening.
- `<directory>/snapshots/<basis, 20 digits>.snapshot`: snapshot files, written to a temporary file in the same directory, forced to disk and renamed, and the directory forced.
- Every file starts with a header: 8 magic bytes (`CAIRNLOG` or `CAIRNSNP`) and the format version as a 4-byte integer, which is 1.

### D3. Records

- A record is a frame: a 4-byte length, a 4-byte CRC32C of the length, a 4-byte CRC32C of the payload, and the payload, so that a damaged length is found as damage. The payload starts with a kind byte: schema transaction or data transaction. Numbers are big-endian. Text is UTF-8 with a length, with an unpaired surrogate written as its own three bytes as in WTF-8, so that every Java string round-trips. Instants are microseconds since the epoch; the open end of a period is written as the largest number, and an instant that microseconds cannot hold exactly is written as seconds and nanoseconds after a marker. Optional values carry a presence byte.
- A data transaction record holds the basis, the transaction time, the principal, the party acted for, the reason, the idempotency key, the writes as submitted (needed to recognize a reused key), the receipt's temporary IDs, created and ended IDs, and every changed entity and relationship: its ID, type, whether it was created, for a relationship its source and target, and its timeline after the commit, each segment with its effective period, values keyed by attribute identifier, and the transaction time and basis that recorded it.
- A schema transaction record holds the basis, the transaction time, the submission, the origin, the definition field by field, the identifier that the registration assigned to each type and attribute it added, and the first identifier free after it.
- The codec is a package of its own in `cairn-core`, with one writer and one reader per record kind, so that a later format version adds a reader beside the old one.

### D4. Recovery

1. Take the lock; fail if another store holds it.
2. Read the newest snapshot file whose header, length and checksum are valid; if none, start empty. A snapshot file that is not valid is skipped, and the next older one is used.
3. Read the log segments in basis order, skip records at or below the snapshot's basis, and apply each record's effects: for the schema, compile the logged definition with the logged identifiers instead of numbering new elements, and append the schema transaction; for data, append the segments to the timelines, add the transaction record, and keep the idempotency key with its transaction and receipt.
4. In the last segment, a bad frame ends the log, and the segment is truncated there, only if fewer bytes remain than a frame header; or its header is valid and its payload runs past the end of the file; or its header is valid, its payload fails its checksum, and only zero bytes follow it; or only zero bytes follow its start. These are what a crash during an append leaves, on file systems that extend a file before its data reaches the disk too; such a record was never acknowledged. Any other bad frame fails the opening, and nothing is truncated. The cost is that damage to only the last record of the log, after it was acknowledged, cannot be told from a crash. A last segment shorter than its file header is deleted.
5. The commit sequence resumes after the last basis and the last transaction time, so new transaction times are later even if the clock went back.
6. A new segment is started for the commits that follow.

### D5. Writing

- `DirectoryStore` wraps the one `InMemoryStore`, which gets an optional journal. Under the writer lock, after the transaction is prepared and before it is recorded and published: encode the record, append it to the current segment, and force the segment to disk (`FileChannel.force(false)`). Only then record and publish. Schema registrations do the same.
- If encoding, writing or forcing fails, the transaction is not recorded, the exception is thrown as `UncheckedIOException`, and the store refuses further writes. The partial record, if any, is what recovery drops (D4.4).
- Dry runs and rejected transactions are not written.

### D6. Snapshots

- A snapshot file holds the state at one basis, in frames like the log's, ending with a frame that counts them: the schema history (each schema transaction as in its log record), every entity and relationship with its timeline at every basis at or below the snapshot's, the transaction records, and the idempotency keys. It is checked whole before it is applied, so a damaged snapshot file never loads half; one in an unknown format version fails the opening. It is written from the store's state at that basis without the writer lock: published state at or below a basis does not change, so commits go on while it is written.
- After a snapshot file is written, snapshot files older than the two newest are deleted, and so are the log segments that hold only records at or below the basis of the older of the two.

### D7. IDs

A generated ID that the store already has, as an entity or a relationship, is skipped, and the generator asked again. Stores already do this for every generator, so a deterministic generator that starts again after recovery cannot reuse an ID.

### D8. Tests

- An equivalence helper compares two stores: the schema history, the transaction records, and for every basis, the timelines of every entity and relationship and the views at the effective times of the reference stories.
- Crash and damage tests write a store, close or abandon it, and then cut or alter bytes of its files.
- A crash is simulated by abandoning a store without closing it, and a failed write by closing the log's channel, through seams that only tests use.
- A randomized test runs random operations against a durable store and an in-memory twin and compares them.
- The scenarios use the reference stories, `TestClock` and temporary directories.

## Risks / Trade-offs

- [Forcing each commit to disk limits throughput to what the disk syncs] → Grouping commits later, behind the same API.
- [A binary format is hard to inspect] → A versioned codec in one place, and tests that read files written by this version; an inspection tool later.
- [Recovery replays the log since the newest snapshot] → `writeSnapshot()` for applications to call, and a snapshot at every close; automatic snapshots later.
- [Effects and snapshot files hold whole timelines per basis, as the in-memory store keeps them, so their size grows with the square of an entity's number of versions] → Acceptable for the sizes an in-memory store holds; the key-value store of ADR-0011 stores versions once, and a later format version can encode timelines as differences.
- [Purging will have to rewrite log and snapshot files] → The purge change designs it; the format version allows it.
