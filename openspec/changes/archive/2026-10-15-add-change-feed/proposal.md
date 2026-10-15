# Proposal

## Why

Side effects belong to applications, fed by a change feed (ADR-0018), and the HR example shows the gap: an approver learns of a waiting leave request only by polling every request. The commit log that ADR-0011 and ADR-0022 expected to feed it exists only in stores kept in a directory, so ADR-0029 builds the feed from an index of what each basis changed, which every kind of store keeps alike.

## What Changes

- `Store` gains `changes(after, limit)`, the changes at the bases after a basis, in basis order, and `changeFeed(after)`, a `java.util.concurrent.Flow.Publisher` of the same changes that waits for the next commits.
- A change is one of: a data transaction with its transaction record and the entities and relationships it changed, each with its type, whether it was created and the effective periods in which it changed; a schema registration with its schema transaction; a purge with its purge record.
- The engine writes the index with each commit, in the same batch; purges delete the entries of what they purge; recovery writes them again. A RocksDB store's format version changes.
- Changes as JSON in `cairn-documents`, and the agent tool `cairn_list_changes`.
- The HR example tells an approver of a waiting request through a subscription.

### Non-goals

- Signals when a future-dated fact becomes effective (ADR-0008).
- Filtering the feed by type or entity, and feeds for replicas.
- Values in changes: consumers read them through views.
- Migrating RocksDB stores written in the earlier format.

## Capabilities

### New Capabilities

- `change-feed`: the changes of a store, in basis order, read from a basis or subscribed to.

### Modified Capabilities

- `json-payloads`: changes written as JSON.
- `agent-tools`: a tool that reads the changes after a basis.

## Impact

- **Decisions:** ADR-0029, accepted under the standing approval with this proposal; it relies on ADR-0008, ADR-0011, ADR-0013, ADR-0017, ADR-0018, ADR-0022, ADR-0024, ADR-0027 and ADR-0028.
- **Code:** `cairn-api` (`Change` and its kinds, `Changed`, the `Store` methods); `cairn-core` (the index keys in `LedgerCodec` and `Ledger`, the feed and its publisher in the stores, the RocksDB format version); `cairn-documents` (JSON); `cairn-agent-tools` (the tool); `cairn-example-hr` (a test).
- **Docs:** a guide to the change feed, the glossary (Change, Change feed), the architecture page, the HR domain page, AGENTS.md, README.md and `openspec/config.yaml`.
