---
status: concluded
date: 2026-10-12
authors: [enricoag1982, Claude Code]
related: [ADR-0005, ADR-0007, ADR-0008, ADR-0009, ADR-0010, ADR-0011, ADR-0012, ADR-0013, ADR-0014, ADR-0015]
---

# Research: the API of a graph-shaped, bitemporal system of record

> [!NOTE]
> sysRec was the working name of Cairn. See [ADR-0016](../decisions/0016-name-the-project-cairn.md).

## Question

What should the API of sysRec look like?
sysRec is a primitive for defining and keeping business and enterprise data. Users define types with inheritance and relationships. The engine stores entities and relationships consistently, with history on two time axes, and answers traversals and complex aggregations.
Every access passes its own parameters plus a standard context (effective time, transaction time, feature flags). The API hides the storage implementation: in memory, a key-value store, or a hybrid of heap and disk.

This note records the brainstorming sessions of 2026-10-10 and 2026-10-12. The decisions moved into ADRs; the rest feeds the first OpenSpec changes.

## Findings

### Framing

- sysRec is less a database than a modeling layer plus an engine. The closest prior art:
  - Reladomo: Java, bitemporal ("business date" and "processing date"), with an optional full cache on the heap.
  - Legend (PURE): a modeling language with classes, inheritance, associations and bitemporal "milestoning".
  - TypeDB: inheritance for entity types and relation types.
  - Datomic and XTDB: immutable history, "the database as a value", queries as of a point in time.
  - Gel (formerly EdgeDB) and Palantir's Ontology: typed object models with links.
- No product domain is chosen yet. A [reference domain](../reference/reference-domain.md) stands in for one in scenarios, tests, examples and benchmarks.
- Hiding the storage behind an interface is easy. Making implementations behave the same is the hard part: consistency, ordering, time precision, paging and errors must be pinned down by the specs and checked by a conformance suite.

### Layers

```text
   applications that embed the engine, batch jobs, tools, tests
                     |
                     v
+---------------------------------------------------------------+
| API      schema API + data API (context, views, transactions, |
|          traversal, query and aggregation)                    |
+---------------------------------------------------------------+
| Core     meta-model and inheritance, versions on two time     |
| engine   axes, constraints, index encoding, query planning    |
+---------------------------------------------------------------+
| SPI      ordered key-value store + commit log                 |
+--------------+------------------+-------------+---------------+
| in-memory    | RocksDB          | hybrid      | FoundationDB  |
+--------------+------------------+-------------+---------------+
```

### The questions and their outcomes

| # | Question | Outcome | Recorded in |
| --- | --- | --- | --- |
| 1 | Embedded library or shared service? | Embedded engine in the application's JVM; a server can follow as an adapter | ADR-0007 |
| 2 | Single writer or distributed writes? | Single writer, snapshot readers | ADR-0007 |
| 3 | What is the in-memory implementation for? | Reference implementation, test double, and production option for data that fits | ADR-0011 |
| 4 | A reference domain? | Yes | [Reference domain](../reference/reference-domain.md) |
| 5 | Language? | Java 25 | ADR-0005 (accepted) |
| 6 | Which kind of graph? | Typed property graph with binary, first-class relationships | ADR-0010 |
| 7 | Version whole entities or single attributes? | Whole entities, with measures that contain the costs | ADR-0009 |
| 8 | Inheritance? | Single inheritance plus traits, polymorphic queries | ADR-0010 |
| 9 | Can an entity change type? Can types evolve? | An entity's type is fixed, roles cover things that come and go; type definitions evolve under compatibility rules | ADR-0010, [below](#type-evolution) |
| 10 | How do polymorphic queries find subtypes? | Index under the concrete type, expand to subtypes when querying | ADR-0010 |
| 11 | Schema-first? Schema stored as data? | Schema-first, stored as data, versioned in transaction time | ADR-0010 |
| 12 | Composition or association? | Both, set per relationship type | ADR-0010 |
| 13 | How are entities identified? | Opaque IDs, generator set per store, UUIDv7 by default | [Below](#identity) |
| 14 | Names of the time axes | "Effective time" and "transaction time" | ADR-0008 |
| 15 | Instants or dates? | UTC instants with microsecond precision; day granularity per entity type | ADR-0008 |
| 16 | Must relationships stay within their endpoints' lifetimes? | Yes, strictly, checked at commit | ADR-0008 |
| 17 | Corrections and future-dated changes | One write operation; effective periods may start in the future | ADR-0008, [below](#writes) |
| 18 | Erasure | Purge: designed now, built later | ADR-0008 |
| 19 | Partial updates | Patch is the default write | [Below](#writes) |
| 20 | When are constraints checked? | At commit; rules spanning several entities after version 1 (tentative) | [Below](#writes) |
| 21 | Context on every call or a bound view? | Immutable view | ADR-0013 |
| 22 | What do feature flags mean? | Typed options, storage capabilities, and flags resolved by the caller | ADR-0013 |
| 23 | Synchronous or asynchronous API? | Synchronous, with streamed results | ADR-0007 |
| 24 | Query language | Queries as data, built through a typed builder | [Below](#queries-and-aggregation) |
| 25 | How consistent are aggregations? | Always one snapshot; columnar copies only make them faster | [Below](#queries-and-aggregation) |
| 26 | What must a storage backend provide? | An ordered key-value store and a commit log | ADR-0011 |
| 27 | Build the heap and disk hybrid ourselves? | In-memory and RocksDB first; a custom split only if measurements need it | ADR-0011 |
| - | How are types defined? | Annotated Java interfaces, processed at compile time | ADR-0012 |
| - | Types defined at runtime? | A follow-up; six rules keep it possible | ADR-0012 |
| - | Are relationships one-directional? | Directed in meaning, navigable both ways, stored once; symmetric types allowed | ADR-0010 |

### Type evolution

Avro and Protobuf evolve how a record is encoded. They know nothing about inheritance, relationships, cardinality, keys, constraints or time, so they cannot be sysRec's type system. They are useful at the edges: schemas generated from sysRec types, for a change feed or a future wire protocol.

sysRec gets its own compatibility checker over the meta-model, borrowing the compatibility modes of schema registries (backward, forward, full, transitive). It runs when a schema change is submitted, and in CI before it is merged.

| Change | Compatible? |
| --- | --- |
| Add an optional attribute, a type or a subtype | yes |
| Add a relationship type | yes, unless it requires a relationship for an entity type that already exists |
| Rename an attribute or type, keeping the old name as an alias | yes |
| Widen a value type (int to long), a multiplicity (exactly one to unbounded) or a relationship target (Organization to Party) | yes |
| Make a required attribute optional, or relax a constraint | yes |
| Move an attribute up to a supertype | yes, if it is optional and not unique |
| Deprecate an attribute (no new writes, history kept) | yes |
| Remove an attribute or type | no: deprecate it instead |
| Narrow anything (long to int, Party to Organization, many to one) | no: needs a migration |
| Make an attribute required, or add a stricter constraint | not until defaults and migrations exist; then only with a default or a backfill, checked against existing data |
| Change a type's supertype | no |

A migration is an explicit, reviewed transformation that runs as a transaction, so it is recorded in history.
The normative list is the `schema-evolution` capability, proposed in the OpenSpec change `add-type-model`.

### Identity

IDs are opaque in the API. The generator is a setting of the store, not of each call, because mixing formats in one store makes keys and indexes unpredictable.

| Generator | Size | Notes |
| --- | --- | --- |
| UUIDv7 (RFC 9562) | 16 bytes | Ordered by time and standard; the default |
| UUIDv8 | 16 bytes | Custom layout, for example with a type tag or partition number |
| ULID | 16 bytes | Like UUIDv7, with a different text encoding |
| 64-bit sequence from the single writer | 8 bytes | The most compact; not unique across stores |
| Snowflake-style 64-bit | 8 bytes | Time, node and sequence; unique across nodes |
| Deterministic | any | Repeatable output in tests |

- Inside a transaction, callers use temporary IDs. The receipt maps them to real ones.
- Callers may supply their own IDs in the store's format, so imports can be repeated safely.
- Natural keys are unique constraints, unique at every point in effective time.
- With whole-entity versions the ID appears once per version, so 16-byte IDs are affordable.

### Writes

- Every write applies to an effective period:
  - **Patch** sets some attributes and leaves the rest unchanged. It is the default.
  - **Replace** sets the complete state; attributes not mentioned are removed.
  - **End-date** ends the entity or relationship from a point in effective time.
- An effective period can start in the future. A future-dated fact becomes visible to reads at later effective times without any transaction, so the change feed is silent at that moment. A scheduler for "now effective" events is a possible follow-up.
- The caller chooses the conflict check: none (the last commit wins), per attribute ("I expect the rating to still be BBB") or per entity. The writer checks it against the caller's basis.
- Partial data never means partial validation. Types, multiplicities, uniqueness and relationship lifetimes are checked at commit, against the state the transaction would produce.
- Rules that span several entities, such as "a party's ownership shares add up to at most 100%", must hold at every point in effective time. They are planned after version 1.

### Feature flags and A/B testing

A gateway assigns callers to populations centrally and puts the resolved flag values into the context. A single call can override flags if the caller is allowed to.
The engine only reads resolved values, reports them in every result, and rejects unknown flags. The details are in ADR-0013.

### Queries and aggregation

- Queries are data structures built through a typed builder: patterns and traversals (recursive ones with a depth limit), filters, projection, grouping and aggregation. A text language, a subset of ISO GQL, can compile to the same structures later.
- Every aggregation runs against a view, so it reflects exactly one snapshot.
- Key-value stores are poor at large scans. The options, which can be combined:
  - scan and aggregate in the engine: always current, fast on the heap, slow from disk
  - aggregates kept up to date on every write: current, but every write pays, and corrections rewrite past totals
  - columnar copies for analytics, each tied to a basis: consistent, but possibly behind
- With columnar copies, a query whose copy is not ready yet waits briefly or scans instead. Consistency never gets weaker; only response time varies. HTAP databases such as SAP HANA combine a store optimized for writes with one optimized for analytics in a similar way.

### Java after 25

| Feature | Status in October 2026 | Why it matters to sysRec |
| --- | --- | --- |
| Compact object headers | In 25 behind a flag; the default in 27 (JEP 534) | Object headers shrink from 12 to 8 bytes: less heap for many small objects |
| Value classes (Valhalla, JEP 401) | Preview in 28 (March 2027), probably still preview in 29 | Dense objects without identity on the heap |
| Vector API | Incubating (12th round in 27) | SIMD instructions for aggregations over columns |
| Structured concurrency | Preview (7th round in 27) | Parallel scans and query branches |
| Scoped values | Final in 25 | Context for tracing inside the engine |
| Ahead-of-time cache (Project Leyden) | Improved in 24 to 26 | Faster startup of applications that embed the engine |
| Prepare to make final mean final (JEP 500) | Warnings in 26 | Avoid libraries that change `final` fields through reflection |

The guidance that follows is in ADR-0005.

## Options and trade-offs

Each ADR lists its options with their pros and cons. Two trade-offs shaped most of the discussion:

- **Versioning whole entities or single attributes.** Whole entities are simpler and keep related attributes consistent. Single attributes make writes, corrections and column scans cheaper. Whole entities won, with measures that contain their costs (ADR-0009).
- **A schema language or Java annotations.** A schema language is concise and independent of programming languages. Annotated interfaces get full editor support and compile-time checks, with no parser to maintain. Annotations won (ADR-0012).

## Conclusion

- ADR-0005 and ADR-0015 are accepted: Java 25, built with Gradle. ADR-0014 (Maven) was rejected.
- ADR-0007 to ADR-0013 record the proposed design and wait for acceptance.
- Candidate product principles, to be agreed and recorded through an ADR before they enter [the principles](../principles.md):
  1. Nothing recorded is lost: history only grows, and only an audited purge removes data.
  2. Consistency over latency: every answer reflects exactly one snapshot.
  3. The same behavior everywhere: every storage implementation passes the same conformance suite.
  4. Values, not managed objects: data is handed out as immutable snapshots, and every change goes through a transaction.
  5. Explicit over implicit: time coordinates, context and flags are passed explicitly and reported back.
  6. The model is a contract: type changes are checked for compatibility before they reach production.
- Still open:
  - The entity ID decisions (opaque IDs, one generator per store, UUIDv7 by default) need an ADR before `add-entity-versions`.
  - "Now effective" events for future-dated facts.
  - Compact internal IDs, or UUIDs used directly as storage keys.
  - Rules that span several entities, tentatively after version 1.
- Next steps, as OpenSpec changes in this order:
  1. `add-type-model`: the meta-model, the programmatic schema API, the schema stored as data, and compatibility checks
  2. `add-entity-versions`: create, patch, replace and end-date entities on two time axes, read through views, in memory
  3. `add-relationships`: relationships as data, integrity at both ends, navigation and traversal
  4. `add-java-type-definitions`: annotations, the annotation processor, generated builders and typed access
  5. `add-durable-commit-log`: a file-based commit log, snapshots and recovery
  6. `add-queries-and-aggregation`
  7. Later: feature flags and storage capabilities, change feed, purge, a RocksDB store, types defined at runtime

## Sources

- Reladomo: <https://github.com/goldmansachs/reladomo>
- Legend: <https://legend.finos.org/>
- TypeDB: <https://typedb.com/>
- Datomic: <https://docs.datomic.com/>
- XTDB: <https://docs.xtdb.com/>
- Martin Fowler, "Bitemporal History": <https://martinfowler.com/articles/bitemporal-history.html>
- Richard T. Snodgrass, "Developing Time-Oriented Database Applications in SQL": <https://www2.cs.arizona.edu/~rts/tdbbook.pdf>
- Kulkarni and Michels, "Temporal features in SQL:2011": <https://sigmodrecord.org/publications/sigmodRecord/1209/pdfs/07.industry.kulkarni.pdf>
- ISO/IEC 39075:2024, GQL: <https://www.iso.org/standard/76120.html>
- RFC 9562, UUIDs (versions 7 and 8): <https://www.rfc-editor.org/rfc/rfc9562>
- OpenFeature: <https://openfeature.dev/>
- Java 26 released (InfoQ): <https://infoq.com/news/2026/03/java26-released>
- JDK 27 and JDK 28, what we know so far (InfoQ): <https://www.infoq.com/news/2026/08/java-27-so-far/>
- JEP 401 value objects preview (InfoQ): <https://www.infoq.com/news/2026/08/jep401-value-objects-preview/>
- Project Valhalla (OpenJDK): <https://openjdk.org/projects/valhalla/>
