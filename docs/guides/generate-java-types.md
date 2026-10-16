---
last-reviewed: 2026-10-16
owner: ""
---

# Generate Java types from schema documents

How to generate Java types from your schema documents, and use them to read entities, build transactions and write rules.
What is generated is decided in [ADR-0021](../decisions/0021-generate-java-types-as-interfaces-with-snapshot-records-builders-of-writes-and-rule-base-classes.md) and specified in the `java-types` spec.
Types without generated code, such as those an agent registers at runtime, stay usable through the generic API ([ADR-0012](../decisions/0012-define-types-as-annotated-java-interfaces-processed-at-compile-time.md)).

## What you get

For each entity type, trait and relationship type, a *generated type*: an interface with an accessor per attribute, which extends the interfaces of its supertype and its traits. Entity and relationship types also get a *snapshot record* and builders.
For the [reference domain's schema document](../reference/reference-model.cairn.yaml), `Person` extends `Party`, which extends `Named`:

```java
public interface Person extends Party {
    String TYPE = "Person";
    OrderedAttributeRef<LocalDate> BIRTH_DATE = AttributeRef.date("birthDate");    // for queries
    Optional<LocalDate> birthDate();          // optional attributes are Optional; required ones are plain

    static Person from(EntityState state) { ... }
    static Create create() { ... }
    static Patch patch(Ref target) { ... }
    static Replace replace(Ref target) { ... }

    record Snapshot(EntityState entityState, String name, Optional<LocalDate> birthDate) implements Person { ... }
}
```

Every type has the constant `TYPE`, its name, and every relationship type also `INVERSE`, its inverse name, such as `Owns.INVERSE`, which is `"ownedBy"`, for navigation, traversals and queries.

The value types map to `String`, `boolean`, `int`, `long`, `BigDecimal`, `LocalDate` and `Instant`. Allowed values stay strings.
Every rule that a document names gets a *rule base class*, such as `LeiChecksumRuleV1`.
The generated code depends on `cairn-api` only.

## Generate with Gradle

Apply the plugin to a Java project, put the documents in `src/main/cairn/` (files ending in `.cairn.yaml` or `.cairn.json`), and name the package:

```kotlin
plugins {
    java
    id("io.github.enricoag1982.cairn.types")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)    // cairn-api, and so the generated code, needs Java 25
    }
}

repositories {
    mavenCentral()    // for the generator's own dependencies, such as SnakeYAML and RE2/J
}

cairn {
    packageName = "com.example.model"
    // schemaDocuments.from("other/dir/crm.cairn.yaml")    // adds to the default documents; setFrom replaces them
}

dependencies {
    cairnCodegen("io.github.enricoag1982.cairn:cairn-codegen:0.1.0-SNAPSHOT")    // the generator
    cairnCodegen("io.github.enricoag1982.cairn:cairn-cel:0.1.0-SNAPSHOT")        // only if your documents declare checks or derived attributes
    implementation("io.github.enricoag1982.cairn:cairn-api:0.1.0-SNAPSHOT")      // what the generated code uses
}
```

The task `generateCairnTypes` writes the types to `build/generated/sources/cairn/java/main` before `compileJava`, and is up to date while the documents, the package, the generator and its Java stay the same.
The generator runs on Java 25 in a process of its own, whatever Java runs Gradle. The project's own code needs Java 25 too, because `cairn-api` does, hence the toolchain above.
A document with problems fails the build, and each problem is reported with the document, line and column.

> [!NOTE]
> Cairn is not published yet. To try the plugin, include this repository in your build: `includeBuild("../sysRec")` inside `pluginManagement` for the plugin, and again at the top level of `settings.gradle.kts` for `cairn-codegen` and `cairn-api`, which Gradle then substitutes with the repository's projects.

Without Gradle, run the generator's main class, `io.github.enricoag1982.cairn.codegen.CairnCodegen`, with the options `--package <name> --output <dir>` and the documents. It refuses to replace an output directory that holds anything but `.java` files.

## Read

`from` reads a state as the type you ask for, and returns the most specific generated type:

```java
View view = store.view(coordinates);
Party party = Party.from(view.entity(id).orElseThrow());
switch (party) {
    case Person person -> System.out.println(person.name() + ", born " + person.birthDate().orElse(null));
    case Organization organization -> System.out.println(organization.name() + " " + organization.lei().orElse(""));
    default -> System.out.println(party.name() + " of type " + party.entityState().entityType());
}
```

Each unique attribute gets a method that makes a *unique key*, so `view.entity(Account.byNumber("A-1"))` finds the account that has the number at the view's effective time.
The interfaces are not sealed, so a switch needs a default branch: a document or an agent can add subtypes later.
A state of a type that the generated code does not know, such as a subtype registered after generation, reads as the type you asked for; its other attributes are in `entityState()`.
Reading a state of a known type that is not the asked type or one of its subtypes throws `IllegalArgumentException`.

## Write

Builders produce the writes of a transaction. They do not check values; the transaction checks them at commit and reports problems as usual. Builders have no setter for a [derived attribute](derive-attributes.md), which the store computes; snapshot records read it.

```java
Transaction transaction = Transaction.by("alice@example.com")
        .write(Organization.create().name("ACME").as("acme").effectiveFrom(january1))
        .write(Organization.create().name("Beta").as("beta").effectiveFrom(january1))
        .write(Owns.relate(new TemporaryId("acme"), new TemporaryId("beta"))
                .share(new BigDecimal("30")).effectiveFrom(march1))
        .build();
store.commit(transaction);
```

`relate` also takes the generated types of the two ends, such as `Owns.relate(acme, beta)` for an `Organization` and a `Party` read before.
`patch(ref)` sets attributes and clears optional ones (`clearLei()`), and `replace(ref)` sets all of them; both take a conflict check with `check(...)`.
A reference can be an ID, a temporary ID or a unique key, such as `Account.patch(Account.byNumber("A-1"))`; the store resolves a key when it commits, at the start of the write's period ([ADR-0026](../decisions/0026-refer-to-entities-by-unique-keys-resolved-at-commit.md)).

## Query

Each generated interface has a typed reference per attribute it declares, named in upper snake case, such as `Account.BALANCE`. It builds query conditions, columns, group keys, orders and aggregates with values of the attribute's Java type, so the compiler checks them:

```java
Query query = Query.from(Account.TYPE, "a")
        .where(Account.BALANCE.of("a").gt(new BigDecimal("1200")))
        .build();
```

Read the rows' bindings as generated types with `from`, such as `Account.from(row.entity("a").orElseThrow())`. The guide [Query a store](query-a-store.md) explains queries.

## Write a rule

Extend the rule's base class, describe it, and check the typed value:

```java
public final class LeiChecksum extends LeiChecksumRuleV1 {

    private static final RuleDescriptor DESCRIPTOR = describe(
            "The LEI's check digits are valid (ISO 17442, ISO 7064 MOD 97-10).",
            new RuleCode("invalid-check-digits", "The two check digits of the LEI do not match its other characters."));

    @Override
    public RuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected void check(Organization organization, RuleContext context) {
        organization.lei()
                .filter(lei -> !validCheckDigits(lei))              // validCheckDigits is yours to write
                .ifPresent(lei -> context.problem("invalid-check-digits", "lei",
                        "The check digits of the LEI " + lei + " are wrong."));
    }
}
```

The base class holds the rule's name, version and type, and turns each entity version into an `Organization`. The description and the codes stay in your class ([ADR-0018](../decisions/0018-validate-with-declarative-constraints-and-named-java-rules.md)).
Give the rule to the store with `StoreSettings.builder().rules(new LeiChecksum())`, or list it for Java's service loader.

A rule can compare the version with what the store held before the transaction: `context.before()` and `context.beforeAt(instant)` are views of the store as committed before it, and the base class's `previous(version, context)` reads the versions that the store held before the transaction during the checked version's period, as its generated type, or none when the transaction creates it. Compare with each of them, so that the verdict holds at every instant of the period. `previous` reads the entity's timeline, so every later change to that timeline runs the rule again ([ADR-0032](../decisions/0032-let-rules-read-the-store-as-committed-before-the-transaction.md)). For example, `lei-kept`, a subclass of its generated `LeiKeptRuleV1`, keeps an LEI once given:

```java
@Override
protected void check(Organization organization, RuleContext context) {
    boolean hadOne = previous(organization, context).stream().anyMatch(before -> before.lei().isPresent());
    if (hadOne && organization.lei().isEmpty()) {
        context.problem("lei-removed", "lei", "An LEI cannot be removed once given.");
    }
}
```

A rule that several types name is generated for their closest common supertype.

Name a rule only on the type it checks, and read everything else through its `RuleContext`: the store records what the rule reads there, and runs it again when a transaction changes that, such as a relationship that the rule navigated and that ends ([ADR-0025](../decisions/0025-run-a-rule-again-when-the-data-it-read-changes.md)). What a rule reads anywhere else, the store cannot see.
Prefer navigating from what the rule checks: a rule that lists the entities of a type, finds an entity by a unique key, runs a query, which starts by listing its start type unless it [starts from given entities](query-a-store.md#in-java), or reads a timeline, runs again whenever any of those entities changes at a time it read.

## Names

Generated names are the names in the documents.
A name that would not compile or would clash with what the generated code adds gets the suffix `_`: an attribute `default` has the accessor `default_()`, and an attribute `id` the accessor `id_()`. Typed references are named after their attribute in upper snake case, such as `BIRTH_DATE`. The `java-types` spec has the full rule.

## Limits

- Typed navigation is not generated; follow relationships with queries or views, and convert with `from`.
- `from` knows only the canonical type names, not aliases of renamed types.
- Renaming a type or attribute in a document renames the generated Java names, so code that uses them must change too.
