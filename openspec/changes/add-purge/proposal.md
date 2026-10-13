# Proposal

## Why

Cairn never changes or deletes what it recorded, but a system of record that holds personal data must be able to erase it for good when the law requires it, history included.
RD-11 of the reference domain asks for it, and it is the last story that Cairn cannot tell yet. ADR-0024 decides how: remove the entities from all of history, rewrite the files of durable stores, and record that a purge happened.

## What Changes

- `Store.purge`: purging entities by ID, with a principal, a party acted for and a reason, removing every version of them at every basis, every relationship from or to them, and the targets of their compositions.
- Refused purges: unknown IDs, and purges that would leave an entity without a relationship its type requires, with problems that name the entities to purge too.
- A record of each purge, with the IDs it removed, listed by the store.
- Purged IDs are never used again, and idempotency keys of transactions that wrote purged data are refused.
- Durable stores write the purge to their log, then a snapshot file without the purged data, and delete every older file before the purge returns; recovery completes a purge that was interrupted.

### Non-goals

- Purging single attributes or single versions: a purge removes whole entities.
- A purge tool for agents, and the approval policy for purges (ADR-0024: later, with approval by default).
- Erasing copies outside the store, such as backups.

## Capabilities

### New Capabilities

- `purge`: purging entities from all of history, refused purges, purge records, what stays unusable, and purges in durable stores

### Modified Capabilities

None. Every other capability keeps its behavior for stores that never purge.

## Impact

- **Decisions:** ADR-0024 (purge), accepted under the standing approval with this proposal; it makes the one exception to the immutability of ADR-0008, and relies on ADR-0022 (durable stores) and ADR-0010 (relationships and compositions).
- **Code:** `cairn-api` gains `Purge`, `PurgeResult` and `PurgeRecord`, and `Store.purge` and `Store.purges`; `cairn-core` removes data from the in-memory store, and durable stores log purges, rewrite their files and complete purges at recovery. No new dependencies.
- **Docs:** the glossary (purge, purge record), the guide for durable stores, the architecture page, the reference domain.
