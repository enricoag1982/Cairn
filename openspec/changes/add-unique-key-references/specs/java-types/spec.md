## ADDED Requirements

### Requirement: Keys of unique attributes

Every generated entity type SHALL get, for each unique attribute it has, including inherited ones, a static method named `by` followed by the attribute's name with its first letter in upper case, which takes a value of the attribute's Java type and returns the unique key of that type, attribute and value (see the `transactions` capability). A method name that would clash SHALL get the suffix `_`, as other generated names do.

#### Scenario: Patching and finding by a generated key

- **WHEN** types are generated for the reference model, a transaction patches `Account.byNumber("A-1")` to the balance 1,500 from 15 March, and a view effective at 31 March finds `Account.byNumber("A-1")`
- **THEN** the transaction is committed, and the view returns A-1 with the balance 1,500
