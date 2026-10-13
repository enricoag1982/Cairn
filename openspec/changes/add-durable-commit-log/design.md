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

- A record is a 4-byte length, a 4-byte CRC32C of the payload, and the payload. The payload starts with a kind byte: schema transaction or data transaction. Numbers are big-endian, text is UTF-8 with a length, instants are microseconds since the epoch, and optional values carry a presence byte.
- A data transaction record holds the basis, the transaction time, the principal, the party acted for, the reason, the idempotency key, the writes as submitted (needed to recognize a reused key), the receipt's temporary IDs, created and ended IDs, and every changed entity and relationship: its ID, type, whether it was created, for a relationship its source and target, and its new segments, each with its effective period and values keyed by attribute identifier.
- A schema transaction record holds the basis, the transaction time, the submission, the origin, the definition field by field, and the identifier that the registration assigned to each element it added.
- The codec is a package of its own in `cairn-core`, with one writer and one reader per record kind, so that a later format version adds a reader beside the old one.

### D4. Recovery

1. Take the lock; fail if another store holds it.
2. Read the newest snapshot file whose header, length and checksum are valid; if none, start empty. A snapshot file that is not valid is skipped, and the next older one is used.
3. Read the log segments in basis order, skip records at or below the snapshot's basis, and apply each record's effects: for the schema, compile the logged definition with the logged identifiers instead of numbering new elements, and append the schema transaction; for data, append the segments to the timelines, add the transaction record, and keep the idempotency key with its transaction and receipt.
4. A record that ends before its length, or whose checksum fails, in the last segment and followed only by zero bytes or nothing, is the end of the log: the segment is truncated there. Any other bad record fails the opening, and nothing is truncated.
5. The commit sequence resumes after the last basis and the last transaction time, so new transaction times are later even if the clock went back.
6. A new segment is started for the commits that follow.

### D5. Writing

- Under the writer lock, after the transaction is prepared and before it is recorded and published: encode the record, append it to the current segment, and force the segment to disk (`FileChannel.force(false)`). Only then record and publish. Schema registrations do the same.
- If encoding, writing or forcing fails, the transaction is not recorded, the exception is thrown as `UncheckedIOException`, and the store refuses further writes. The partial record, if any, is what recovery drops (D4.4).
- Dry runs and rejected transactions are not written.

### D6. Snapshots

- A snapshot file holds the state at one basis: the schema history (each schema transaction as in its log record), every entity and relationship with all its segments recorded at or below the basis, the transaction records, and the idempotency keys. It is written from the store's state at that basis without the writer lock: published state at or below a basis does not change, so commits go on while it is written.
- After a snapshot file is written, snapshot files older than the two newest are deleted, and so are the log segments that hold only records at or below the basis of the older of the two.

### D7. IDs

A generated ID that the store already has, as an entity or a relationship, is skipped, and the generator asked again. This holds for every store and generator, so a deterministic generator that starts again after recovery cannot reuse an ID.

### D8. Tests

- An equivalence helper compares two stores: the schema history, the transaction records, and for every basis, the timelines of every entity and relationship and the views at the effective times of the reference stories.
- Crash and damage tests write a store, close or abandon it, and then cut or alter bytes of its files.
- A failed write is simulated with a package-private seam that makes the log's channel fail.
- The scenarios use the reference stories, `TestClock` and temporary directories.

## Risks / Trade-offs

- [Forcing each commit to disk limits throughput to what the disk syncs] → Grouping commits later, behind the same API.
- [A binary format is hard to inspect] → A versioned codec in one place, and tests that read files written by this version; an inspection tool later.
- [Recovery replays the log since the newest snapshot] → `writeSnapshot()` for applications to call, and a snapshot at every close; automatic snapshots later.
- [Purging will have to rewrite log and snapshot files] → The purge change designs it; the format version allows it.
