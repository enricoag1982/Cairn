## ADDED Requirements

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

#### Scenario: A function of Cairn's with the wrong arguments

- **WHEN** Person declares the check `born-on-a-working-day` with the expression `cairn.workingDays(self.name, self.birthDate) > 0`
- **THEN** validation fails with a problem of the code `INVALID_CHECK` located at `Person.born-on-a-working-day`
