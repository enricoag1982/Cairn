## MODIFIED Requirements

### Requirement: Names in Java

Generated names SHALL be the names in the documents. A name that would not compile or would clash SHALL get the suffix `_`, repeated until it clashes with nothing: Java keywords, restricted identifiers and the literals `true`, `false` and `null`; for attributes also the methods of `Object`, the simple names of library types that generated code uses, and the names of members that generated code adds to types and builders; and rule base class names that equal type names. The typed reference of an attribute SHALL be a constant named after the attribute in upper snake case, such as `BIRTH_DATE` for `birthDate`, with the suffix `_`, repeated, where it would clash with `TYPE`, `INVERSE`, a generated type, or a constant that the type inherits. Every relationship type SHALL have the constant `INVERSE`, its inverse name, as every type has the constant `TYPE`, its name. Generated code SHALL compile whatever the names of the types, including names of Java library types.

#### Scenario: An attribute named like a Java keyword

- **WHEN** types are generated for a document whose entity type Setting has the attribute `default`
- **THEN** Setting has the accessor `default_`, and the generated code compiles without warnings

#### Scenario: Types named like Java library types

- **WHEN** types are generated for a document with the entity types `Map`, `Optional` and `String`
- **THEN** the generated code compiles without warnings

#### Scenario: The inverse name as a constant

- **WHEN** types are generated for the reference domain's document
- **THEN** `Owns.INVERSE` is `ownedBy`, `HeldBy.INVERSE` is `holds`, and `Owns.TYPE` is `Owns`

