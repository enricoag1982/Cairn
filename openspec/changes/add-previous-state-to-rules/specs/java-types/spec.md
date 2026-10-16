## MODIFIED Requirements

### Requirement: Rule base classes

Every rule that the documents name SHALL get an abstract base class per name and version, which implements the rule interface for its kind of type, holds the rule's name, version and type, and passes each version to a typed check that subclasses write. A base class SHALL also read the checked entity or relationship as it was committed before the transaction, at the start of the version's period, as its generated type, or nothing if the store held none there. The type SHALL be the type that names the rule, or, if several types name it, their closest common supertype. The description and the problem codes SHALL stay in the subclass.

#### Scenario: A rule written against a generated type

- **WHEN** the rule `lei-checksum` is written as a subclass of its generated base class, a store is created with it, and the transaction of RD-12 is committed
- **THEN** the transaction is rejected with a problem whose code is `lei-checksum/invalid-check-digits`

#### Scenario: A rule that several types name

- **WHEN** types are generated for documents in which Person and Organization both name the rule `contact-known`
- **THEN** the base class of `contact-known` checks a Party

#### Scenario: The previous version in a generated rule

- **WHEN** the test-only rule `lei-kept` is written as a subclass of its generated base class, which reads ACME's previous version with it, and a transaction removes ACME's LEI from 1 March after RD-9
- **THEN** the transaction is rejected with a problem whose code is `lei-kept/lei-removed`

