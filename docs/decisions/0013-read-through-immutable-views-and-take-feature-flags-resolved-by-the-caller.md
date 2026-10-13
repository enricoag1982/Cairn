---
status: accepted
date: 2026-10-13
decision-makers: [enricoag1982]
consulted: []
informed: []
---

# Read through immutable views and take feature flags resolved by the caller

## Context and Problem Statement

Every access to Cairn carries a standard context besides its own parameters: time coordinates ([ADR-0008](0008-record-history-on-two-time-axes-effective-time-and-transaction-time.md)), who is asking, limits and feature flags.
Feature flags must support gradual rollout and A/B testing: a gateway assigns callers to populations centrally, and single calls can override flags.
We need to decide how the context is passed and how flags reach the engine.
See the [API research note](../research/2026-10-12-graph-system-of-record-api.md).

## Decision Drivers

- Repeatable reads: several reads in one piece of work see the same data
- Behavior is deterministic and reproducible from the context alone
- No hidden state in the API
- A/B testing with populations assigned centrally and overrides per call
- Different storage implementations support different features

## Considered Options

- Immutable views bound to a context, with flags resolved by the caller
- An explicit context parameter on every call
- An implicit context in a thread-local or scoped value
- The engine evaluates feature flags itself, through a flag provider

## Decision Outcome

Chosen option: "Immutable views bound to a context, with flags resolved by the caller".

### The context

- *Time:* effective at (or an effective range), and known at (a transaction time or a basis).
- *Identity:* principal and tenant.
- *Consistency:* a minimum basis, for reading your own writes.
- *Limits:* deadline, page size and traversal budget.
- *Tracing:* correlation id. Writes also carry an idempotency key and a reason.
- *Features:* typed options and feature flags.

### Views

- A read context opens a **view**. The view fixes its effective time, its basis and the meaning of "now" when it is created, and never changes. Every read through one view is repeatable.
- The public API has no implicit context. Engine internals may use scoped values for tracing.

### Three kinds of "feature"

- *Typed options* change what a query means, for example "include history". They are typed parameters, not flags.
- *Storage capabilities* are features a storage implementation may lack. They are discoverable at runtime, and asking for a missing one fails immediately.
- *Feature flags* switch behavior for rollout and A/B testing.

### Feature flags

- The engine never decides who belongs to which population. A gateway or flag service evaluates targeting and puts the resolved values into the context. The engine only reads them.
- Unknown flags are rejected.
- Every result reports the context and flags that were applied.
- Overrides per call are allowed only for callers permitted to make them. The gateway checks this.
- Flags that change what gets stored are rare, and the applied flags are recorded in the transaction's metadata.

### Consequences

- Good, because the same context always gives the same behavior, so results can be reproduced and experiments analyzed.
- Good, because views make repeatable reads trivial and are easy to pass around.
- Good, because the engine needs no flag service and stays deterministic in tests.
- Bad, because callers must create and close views. Short helper methods cover single reads.
- Bad, because every deployment needs a gateway or flag service to assign populations.

### Confirmation

Conformance scenarios check that reads through one view are repeatable, that unknown flags are rejected, and that results report the applied context.

## Pros and Cons of the Options

### An explicit context on every call

- Good, because nothing is bound or hidden.
- Bad, because two calls can use different snapshots by mistake, unless every call also passes a basis.

### An implicit context

- Good, because method signatures stay short.
- Bad, because hidden state makes behavior hard to test and breaks across threads.

### The engine evaluates flags

- Good, because callers need no flag service.
- Bad, because targeting rules enter the engine, and behavior depends on more than the context.

## More Information

- OpenFeature's "evaluation context" is a similar idea, for the gateway side.
- Shadow mode suits an engine: run the old and the new path, return the old result, and log any difference.
