# Proposal

## Why

Checks and derived attributes are CEL expressions over one version's own values (ADR-0030, ADR-0031). Standard CEL cannot count the working days between two dates, so the HR example enters a leave's `days` by hand, and nothing keeps them in step with the dates. The HR domain page lists this as the gap "Derived values from other data".

## What Changes

- Expressions in checks and derivations can call two functions of Cairn's own: `cairn.calendarDays(first, last)` and `cairn.workingDays(first, last)`. Each takes two timestamps and gives, as an integer, the number of days, or of days from Monday to Friday, from the first date to the last, in UTC, both included; 0 when the last is before the first.
- The functions read nothing but their arguments, so derived values keep depending only on their own version.
- A call with arguments of other types, or of a function that the language does not offer, is a compilation problem, as today.
- The HR example derives a leave's `days` with `cairn.workingDays(self.firstDay, self.lastDay)`.

### Non-goals

- Public holidays, other working weeks and calendars as data.
- Derived values that read other entities.
- Functions with a clock, such as today's date.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `checks`: functions of Cairn's own in expressions, which derivations share.

## Impact

- **Decisions:** ADR-0035, accepted under the standing approval: expressions get functions of Cairn's own over a version's values, starting with counts of days. It adds to ADR-0030 and ADR-0031.
- **Code:** `cairn-cel` (the function declarations and their implementations); `cairn-example-hr` (`days` derived, its writers and the generated company).
- **Docs:** the write-checks and derive-attributes guides, the glossary, the reference domain page (test-only derived attributes), the HR domain page (type table, gaps) and the architecture page.
