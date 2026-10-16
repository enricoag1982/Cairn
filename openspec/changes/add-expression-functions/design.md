# Design

## Context

ADR-0035 decides this change: expressions get functions of Cairn's own, pure functions of their arguments, named `cairn.<name>`, starting with counts of days.

`cairn-cel`'s `CelLanguage` builds one CEL environment per set of attributes, with the standard library and macros, and `self` as a struct. Dates and instants reach CEL as timestamps, a date at the start of its day in UTC. Checks and derivations share the environment, so a function declared there is available in both.

## Goals / Non-Goals

**Goals:** count calendar and working days between two dates in checks and derivations, deterministically, and derive the HR example's leave days from their dates.

**Non-goals:** see the proposal.

## Decisions

### D1. The functions

- `cairn.calendarDays(timestamp, timestamp) -> int`: the number of days from the UTC date of the first argument to the UTC date of the second, both included; 0 if the second date is before the first.
- `cairn.workingDays(timestamp, timestamp) -> int`: the same, counting only Mondays to Fridays.
- Both are global functions with these qualified names, declared in the environment with their overloads, so that the checker types them and reports a call with other argument types as a compilation problem. Their implementations compute in `java.time`, never with the default time zone, and in constant time for working days (whole weeks, then the rest), so that the evaluation cost stays small for any range.
- Errors: the timestamps of CEL cover years 1 to 9999, and the counts fit an int64, so the functions never fail on their arguments.

### D2. Where they apply

The same environment compiles checks and derivations, so both can call the functions. Problems stay as they are: `INVALID_CHECK` or `INVALID_DERIVATION` with the position, for a wrong call. A derivation into a `decimal` attribute converts with `double(...)`, as for any integer.

### D3. Documentation of the library

The write-checks guide lists Cairn's functions, and the derive-attributes guide points to it. The functions' descriptions say that they count in UTC and include both dates.

### D4. The reference domain and the HR example

- **Test-only derived attributes** on Person, for the scenarios: `workingDaysTo2026` (int, optional): `has(self.birthDate) ? dyn(cairn.workingDays(self.birthDate, timestamp('2025-12-31T00:00:00Z'))) : null`, and `calendarDaysTo2026` with `cairn.calendarDays` in its place.
- **HR.** `LeaveRequest.days` becomes derived: `double(cairn.workingDays(self.firstDay, self.lastDay))`. It stays required and decimal. Writers stop giving it: the stories, the agent tasks if any, and the generated company. The days of every story stay the same, because their periods start on Mondays and their counts are already working days; a story whose count differs is corrected and said so.

## Risks / Trade-offs

- **A schema change for HR.** Making `days` derived is breaking (ADR-0031); the example registers its schema from scratch, so it is only a change of the document.
- **Working days are Monday to Friday everywhere.** The guide says so, and holidays stay a gap.
