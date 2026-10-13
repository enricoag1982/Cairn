# Spec Delta

## Purpose

Defines the Java types generated from schema documents: interfaces and snapshot records for reading, builders for writing, base classes for rules, how names map to Java, and the generator with its Gradle plugin.

## ADDED Requirements

### Requirement: Generated types

THE SYSTEM SHALL generate Java source from a set of schema documents, into one Java package that the caller names. Every entity type, trait and relationship type SHALL become a public interface with an accessor for each attribute it declares, extending the interfaces of its supertype and of its traits. Every entity type's interface SHALL extend `TypedEntity`, and every relationship type's interface `TypedRelationship`, which give the ID and the generic state, and for a relationship its source and target. A required attribute SHALL have the Java type of its value type (`String`, `boolean`, `int`, `long`, `BigDecimal`, `LocalDate` or `Instant`), and an optional attribute an `Optional` of it. Allowed values SHALL stay strings. A deprecated type or attribute SHALL be deprecated in Java. The generated source SHALL depend on `cairn-api` only, SHALL compile without warnings, and SHALL be the same for the same documents.

#### Scenario: Interfaces for the reference model

- **WHEN** types are generated for the reference domain's schema document
- **THEN** Party is an interface that extends Named, Person and Organization extend Party, Person's `birthDate` is an optional `LocalDate`, and Owns has the accessor `share` of type `BigDecimal`

### Requirement: Reading states as generated types

Every entity and relationship type SHALL have a snapshot record: an immutable record that holds the generic state it was read from and every attribute of the type. `from`, on the interface of a type, SHALL read a state as that type: as the snapshot record of the state's own type if it is that type or a generated subtype, and as the snapshot record of the asked type if the state's type is one that the generated code does not know. Reading a state of a generated type that is neither the asked type nor one of its subtypes, or a state that lacks a required value, SHALL fail as a programming error.

#### Scenario: Reading Alice as a Person

- **WHEN** the transactions of RD-1 are recorded, and Alice's state is read as Person
- **THEN** the result has the name Alice, no birth date, and the ID of Alice's state

#### Scenario: Reading an organization as a party

- **WHEN** the transactions of RD-1 are recorded, and ACME's state is read as Party
- **THEN** the result is an Organization with the name ACME

#### Scenario: A subtype registered after generation

- **WHEN** a schema that adds the entity type Bank with the supertype Organization is registered after the types were generated, a Bank is created, and its state is read as Organization
- **THEN** the result is an Organization whose state has the type Bank

#### Scenario: A state of another type

- **WHEN** the state of an Address is read as Person
- **THEN** reading fails with an exception

### Requirement: Building writes

Every entity type that is not abstract SHALL have a builder for creating an entity, and every entity type builders for patching and replacing one; every relationship type SHALL have builders for relating two entities, patching and replacing. A builder SHALL have a setter for each attribute of the type, a patch builder SHALL also clear optional attributes, and every builder SHALL produce the write of the generic API for an effective period. A builder for relating SHALL also take the generated types of the relationship type's source and target. Builders SHALL NOT check values: the transaction checks them at commit.

#### Scenario: Creating Alice with a builder

- **WHEN** a transaction creates a Person with the builder, with the name Alice, from 1 January, and is committed
- **THEN** the transaction is committed, and Alice's state read as Person has the name Alice

#### Scenario: A builder does not check values

- **WHEN** a transaction creates a Person with the builder, without a name, and is committed
- **THEN** the commit is rejected with the problem that a required value is missing

#### Scenario: Relating with a builder

- **WHEN** ACME and Beta exist, and a transaction relates ACME to Beta with the builder of Owns, from the generated types of both, with the share 30, from 1 March
- **THEN** the transaction is committed, and the relationship's state read as Owns has the share 30 and ACME as its source

#### Scenario: Clearing an optional value with a builder

- **WHEN** ACME has an LEI, and a transaction patches ACME with the builder of Organization, clearing its LEI from 1 March
- **THEN** ACME's state from 1 March, read as Organization, has no LEI

### Requirement: Rule base classes

Every rule that the documents name SHALL get an abstract base class per name and version, which implements the rule interface for its kind of type, holds the rule's name, version and type, and passes each version to a typed check that subclasses write. The type SHALL be the type that names the rule, or, if several types name it, their closest common supertype. The description and the problem codes SHALL stay in the subclass.

#### Scenario: A rule written against a generated type

- **WHEN** the rule `lei-checksum` is written as a subclass of its generated base class, a store is created with it, and the transaction of RD-12 is committed
- **THEN** the transaction is rejected with a problem whose code is `lei-checksum/invalid-check-digits`

#### Scenario: A rule that several types name

- **WHEN** types are generated for documents in which Person and Organization both name the rule `contact-known`
- **THEN** the base class of `contact-known` checks a Party

### Requirement: Names in Java

Generated names SHALL be the names in the documents. A name that would not compile or would clash SHALL get the suffix `_`, repeated until it clashes with nothing: Java keywords, restricted identifiers and the literals `true`, `false` and `null`; for attributes also the methods of `Object`, the simple names of library types that generated code uses, and the names of members that generated code adds to types and builders; and rule base class names that equal type names. Generated code SHALL compile whatever the names of the types, including names of Java library types.

#### Scenario: An attribute named like a Java keyword

- **WHEN** types are generated for a document whose entity type Setting has the attribute `default`
- **THEN** Setting has the accessor `default_`, and the generated code compiles without warnings

#### Scenario: Types named like Java library types

- **WHEN** types are generated for a document with the entity types `Map`, `Optional` and `String`
- **THEN** the generated code compiles without warnings

### Requirement: Generating from documents

The generator SHALL read the documents it is given, in any order, and check them together as a schema registry would register them. WHEN they have problems, THE SYSTEM SHALL report every problem located in its document, and SHALL write nothing. The generator SHALL run from the command line, with the package, an output directory and the documents.

#### Scenario: Documents with problems

- **WHEN** types are generated for a document whose entity type Organization names the supertype `Prty`
- **THEN** the generation fails with a problem located in the document at Organization, and no file is written

#### Scenario: Documents that depend on each other

- **WHEN** types are generated for two documents, given in either order, where the first declares a relationship type whose source is an entity type of the second
- **THEN** types are generated for both documents

### Requirement: A Gradle plugin

THE SYSTEM SHALL provide a Gradle plugin that, in a Java project, generates the types of the project's schema documents into the build directory before the Java code compiles, with a package that the project configures. The plugin SHALL run the generator on Java 25 even when Gradle runs on an older Java. The generation SHALL be skipped when its documents and settings did not change, and a document with problems SHALL fail the build with the problems and their locations.

#### Scenario: Generating in a Gradle build

- **WHEN** a Java project applies the plugin with the reference domain's schema document, and compiles a class that creates a Person with the generated builder
- **THEN** the build succeeds

#### Scenario: Nothing changed

- **WHEN** the project is built again without changes
- **THEN** the generation is up to date

#### Scenario: Gradle on an older Java

- **WHEN** the project is built with Gradle running on a Java older than 25
- **THEN** the types are generated, and the build succeeds

#### Scenario: A document with problems fails the build

- **WHEN** the project's document names the supertype `Prty`
- **THEN** the build fails, and its output names the document with the line and column of the problem
