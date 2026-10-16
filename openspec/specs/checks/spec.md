# checks Specification

## Purpose
Defines checks: expressions in the Common Expression Language that entity types and relationship types declare in their definitions, which every version of their values must satisfy; how they are declared and inherited, compiled at registration, run at commit, and what happens without an expression language.

## Requirements

### Requirement: Checks on types

Entity types and relationship types MAY declare checks, each with a name, an expression in the Common Expression Language (CEL) and an optional message. A check name SHALL follow the rules for rule names, and an expression SHALL NOT be blank and SHALL have at most 4,000 characters. A check SHALL apply to the type that declares it and to its subtypes, and a type MUST NOT declare a check whose name it or one of its supertypes already uses for a check, an attribute or a rule. The schema SHALL report, for each type, the checks it declares and those it inherits.

#### Scenario: A check on Account

- **WHEN** Account declares the check `balance-not-negative` with the expression `self.balance >= 0.0`, and the definition is validated with an expression language
- **THEN** validation succeeds, and the schema reports the check on Account

#### Scenario: A check inherited from a supertype

- **WHEN** Party declares the check `name-not-empty` with the expression `size(self.name) > 0`, and the schema is queried for the checks of Organization
- **THEN** the answer contains `name-not-empty`, inherited from Party

#### Scenario: A check declared twice in a hierarchy

- **WHEN** Party and its subtype Organization both declare the check `name-not-empty`
- **THEN** validation fails with a problem located at `Organization.name-not-empty`

#### Scenario: A check named like an attribute

- **WHEN** Account declares a check named `balance`
- **THEN** validation fails with a problem located at `Account.balance`, which says the name is taken by an attribute

#### Scenario: An invalid check name

- **WHEN** Account declares a check named `Balance_Check`
- **THEN** validation fails with a problem located at `Account.Balance_Check`

### Requirement: Checks are compiled at registration

THE SYSTEM SHALL compile every check of a definition when it is validated, planned or registered. An expression SHALL see one variable, `self`, which maps the names of the attributes of a version that have a value to those values: strings and allowed values as CEL strings, booleans as booleans, int and long values as integers, decimals as doubles, and dates and instants as timestamps, a date at the start of its day in UTC. An expression that does not compile, that names through `self` an attribute that the type does not have, or whose result cannot be a boolean, SHALL be a problem with the code `INVALID_CHECK` located at the check, which gives the line and column in the expression where they are known. A registry without an expression language SHALL reject a definition that declares checks with a problem of the code `EXPRESSIONS_UNAVAILABLE` at each check, whose hint names the module `cairn-cel`.

#### Scenario: An expression that does not compile

- **WHEN** Account declares the check `balance-not-negative` with the expression `self.balance >=`
- **THEN** validation fails with a problem of the code `INVALID_CHECK` located at `Account.balance-not-negative`, which gives the column in the expression

#### Scenario: An unknown attribute in a check

- **WHEN** Account declares the check `amount-not-negative` with the expression `self.amount >= 0.0`
- **THEN** validation fails with a problem of the code `INVALID_CHECK` located at `Account.amount-not-negative`, which names `amount`

#### Scenario: No expression language

- **WHEN** a schema registry without an expression language registers a definition in which Account declares the check `balance-not-negative`
- **THEN** the registration is rejected with a problem of the code `EXPRESSIONS_UNAVAILABLE` located at `Account.balance-not-negative`, whose hint names `cairn-cel`

### Requirement: Checks run at commit

WHEN a transaction writes or a composition cascade changes a version of an entity or relationship, THE SYSTEM SHALL run the checks that apply to its type on every segment of its timeline that the transaction writes, with that segment's values, together with the declarative constraints and before Java rules. A check whose expression is false SHALL be a problem with the code `CHECK_VIOLATED`, the check's name and its message; a check whose evaluation fails, for example by reading an attribute without a value without testing it with `has`, or by taking more than 10,000 evaluation steps, SHALL be a problem with the code `CHECK_FAILED`, the check's name and the error. Either SHALL reject the transaction. Checks SHALL NOT read anything but the version they check, and SHALL NOT cause re-checks. A store whose schema declares checks, opened without an expression language, SHALL reject a write to a type that a check applies to with a problem of the code `EXPRESSIONS_UNAVAILABLE`, and SHALL accept other writes. A recorded check that the store's expression language cannot compile when the store is opened SHALL make every write to the types it applies to fail with `CHECK_FAILED`.

#### Scenario: A violated check

- **WHEN** Account declares the check `balance-not-negative` with the message "A balance cannot be negative.", the transactions of RD-5 are committed, and a transaction sets A-1's balance to -5 from 1 April
- **THEN** the transaction is rejected with a problem of the code `CHECK_VIOLATED` located at A-1, which names `balance-not-negative` and has its message

#### Scenario: A check that holds

- **WHEN** Account declares the check `balance-not-negative`, the transactions of RD-5 are committed, and a transaction sets A-1's balance to 0 from 1 April
- **THEN** the transaction commits

#### Scenario: An attribute without a value

- **WHEN** Person declares the check `born-before-2026` with the expression `self.birthDate < timestamp('2026-01-01T00:00:00Z')`, and Alice is created without a birth date
- **THEN** the transaction is rejected with a problem of the code `CHECK_FAILED` that names `born-before-2026`; with the expression `!has(self.birthDate) || self.birthDate < timestamp('2026-01-01T00:00:00Z')` it commits

#### Scenario: A check on a relationship

- **WHEN** Employs declares the check `title-not-blank` with the expression `!has(self.title) || size(self.title) > 0`, and the transaction of RD-3 is committed with the title ""
- **THEN** the transaction is rejected with a problem of the code `CHECK_VIOLATED` that names `title-not-blank`

#### Scenario: A store opened without an expression language

- **WHEN** a durable store whose schema declares `balance-not-negative` on Account holds the transactions of RD-5, and is opened again without an expression language
- **THEN** a transaction that sets A-1's balance is rejected with a problem of the code `EXPRESSIONS_UNAVAILABLE`, and a transaction that renames Alice commits

### Requirement: Functions of Cairn's own in expressions

Expressions in checks and in derived attributes (see the `derived-attributes` capability) SHALL be able to call `cairn.calendarDays(first, last)` and `cairn.workingDays(first, last)`. Each SHALL take two timestamps and give, as an integer, the number of days, or of days from Monday to Friday, from the date of the first to the date of the last, as dates in UTC, both included, and 0 when the last date is before the first. These functions SHALL read nothing but their arguments, and SHALL give the same result on every machine. A call of either with arguments of other types, or of a function of Cairn's that the language does not offer, SHALL be a problem of the check or derivation that calls it, as any expression that does not compile is.

#### Scenario: Working days across a weekend

- **WHEN** Person declares the test-only derived attribute `workingDaysTo2026`, and a person born on Friday 26 December 2025 is created
- **THEN** the person's `workingDaysTo2026` is 4, counting 26, 29, 30 and 31 December

#### Scenario: Calendar days, both included

- **WHEN** Person declares the test-only derived attribute `calendarDaysTo2026`, and a person born on 26 December 2025 is created
- **THEN** the person's `calendarDaysTo2026` is 6

#### Scenario: A last day before the first

- **WHEN** Person declares the test-only derived attribute `workingDaysTo2026`, and a person born on 2 January 2026 is created
- **THEN** the person's `workingDaysTo2026` is 0

#### Scenario: A check that counts days

- **WHEN** Person declares the test-only check `born-on-a-weekday` with the expression `!has(self.birthDate) || cairn.workingDays(self.birthDate, self.birthDate) == 1`, and a person born on Saturday 27 December 2025 is created
- **THEN** the transaction is rejected with a problem of the code `CHECK_VIOLATED` that names `born-on-a-weekday`

#### Scenario: A function of Cairn's with the wrong arguments

- **WHEN** Person declares the check `born-on-a-working-day` with the expression `cairn.workingDays(self.name, self.birthDate) > 0`
- **THEN** validation fails with a problem of the code `INVALID_CHECK` located at `Person.born-on-a-working-day`
