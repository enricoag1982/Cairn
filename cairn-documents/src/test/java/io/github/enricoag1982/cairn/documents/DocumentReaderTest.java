package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static io.github.enricoag1982.cairn.schema.ValueType.DATE;
import static io.github.enricoag1982.cairn.schema.ValueType.DECIMAL;
import static io.github.enricoag1982.cairn.schema.ValueType.INT;
import static io.github.enricoag1982.cairn.schema.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Origin;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DocumentReaderTest {

    @Test
    void aDocumentWithOnlyAnOriginHasNoTypes() {
        SchemaDocument document = valid("cairn: 1\norigin:\n  name: reference-model\n  version: \"2.1.0\"\n");

        assertThat(document.origin()).isEqualTo(new Origin("reference-model", "2.1.0"));
        assertThat(document.definition()).isEqualTo(SchemaDefinition.EMPTY);
    }

    @Test
    void leftOutPropertiesTakeTheirDefaults() {
        SchemaDefinition definition = valid(document(
                "traits:",
                "  Named: {}",
                "entityTypes:",
                "  Party:",
                "    attributes:",
                "      name: { type: string }",
                "relationshipTypes:",
                "  Owns: { source: Party, target: Party, targetsPerSource: '0..*', sourcesPerTarget: '0..*', inverseName: ownedBy }"))
                .definition();

        assertThat(definition.traits()).containsExactly(
                new TraitDefinition("Named", List.of(), List.of(), false, Optional.empty()));
        assertThat(definition.entityTypes()).containsExactly(new EntityTypeDefinition("Party", false, Optional.empty(),
                List.of(), List.of(AttributeDefinition.builder("name", STRING).build()), List.of(), false,
                Optional.empty(), List.of()));
        assertThat(definition.relationshipTypes()).containsExactly(new RelationshipTypeDefinition("Owns", "Party", "Party",
                Multiplicity.ZERO_OR_MORE, Multiplicity.ZERO_OR_MORE, "ownedBy", List.of(), RelationshipKind.ASSOCIATION,
                false, Optional.empty(), List.of(), List.of(), false, Optional.empty(), List.of()));
    }

    @Test
    void everyPropertyIsRead() {
        String text = """
                $schema: "urn:cairn:schema-document:1"
                cairn: 1
                origin: { name: kitchen-sink, version: "3.0" }
                traits:
                  Named:
                    description: Something with a name.
                    aliases: [Labelled]
                    deprecated: true
                    attributes:
                      name: { type: string, required: true }
                entityTypes:
                  Party:
                    description: Anybody.
                    abstract: true
                    traits: [Named]
                  Person:
                    supertype: Party
                    aliases: [Human, Individual]
                    deprecated: true
                    rules:
                      - { name: person-rule, version: 2 }
                    checks:
                      email-set:
                        expression: has(self.email)
                        message: A person has an email.
                      age-known: { expression: "!has(self.age) || self.age >= 0" }
                    attributes:
                      email:
                        type: string
                        required: true
                        unique: true
                        aliases: [mail]
                        deprecated: true
                        description: The address.
                        examples: [a@example.org]
                        allowedValues: [a@example.org, b@example.org]
                        pattern: "[a-z@.]+"
                        minLength: 3
                        maxLength: 40
                      age:
                        type: int
                        range: { atLeast: 0, lessThan: 150 }
                        examples: [42]
                      born: { type: date, examples: ["1990-04-01"] }
                      contact:
                        type: string
                        required: true
                        derived: "has(self.email) ? self.email : 'none'"
                      weight: { type: decimal, range: { greaterThan: 0, atMost: 700.5 } }
                relationshipTypes:
                  Knows:
                    description: Two people know each other.
                    source: Person
                    target: Person
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: knownBy
                    inverseAliases: [knownTo]
                    kind: association
                    symmetric: true
                    aliases: [Acquainted]
                    deprecated: true
                    rules: [{ name: knows-rule, version: 1 }]
                    checks: { since-set: { expression: "has(self.since)", message: Since when? } }
                    attributes:
                      since: { type: date }
                  CloseTo:
                    supertype: Knows
                    source: Person
                    target: Person
                    targetsPerSource: "0..1"
                    sourcesPerTarget: "1..*"
                    inverseName: closeTo
                    kind: composition
                    outlivesTarget: true
                """;
        SchemaDefinition definition = valid(text).definition();

        assertThat(List.of(Keys.DOCUMENT, Keys.ORIGIN, Keys.TRAIT, Keys.ENTITY_TYPE, Keys.RELATIONSHIP_TYPE, Keys.ATTRIBUTE,
                Keys.RANGE, Keys.RULE, Keys.CHECK)).allSatisfy(keys -> assertThat(keys).allSatisfy(key ->
                        assertThat(text).as("the document uses the key %s", key)
                                .containsPattern("(?<![\\w$])" + Pattern.quote(key) + ":")));
        assertThat(definition.traits()).containsExactly(new TraitDefinition("Named",
                List.of(AttributeDefinition.builder("name", STRING).required().build()), List.of("Labelled"), true,
                Optional.of("Something with a name.")));
        assertThat(definition.entityTypes()).containsExactly(
                new EntityTypeDefinition("Party", true, Optional.empty(), List.of("Named"), List.of(), List.of(), false,
                        Optional.of("Anybody."), List.of()),
                new EntityTypeDefinition("Person", false, Optional.of("Party"), List.of(), List.of(
                        new AttributeDefinition("email", STRING, true, true, Optional.empty(), List.of("mail"), true,
                                Optional.of("The address."), List.of(Value.of("a@example.org")),
                                List.of("a@example.org", "b@example.org"), Optional.of("[a-z@.]+"), OptionalInt.of(3),
                                OptionalInt.of(40)),
                        AttributeDefinition.builder("age", INT).range(Range.between(
                                Bound.inclusive(Value.of(0)), Bound.exclusive(Value.of(150))))
                                .examples(Value.of(42)).build(),
                        AttributeDefinition.builder("born", DATE).examples(Value.of(LocalDate.of(1990, 4, 1))).build(),
                        AttributeDefinition.builder("contact", STRING).required()
                                .derived("has(self.email) ? self.email : 'none'").build(),
                        AttributeDefinition.builder("weight", DECIMAL).range(Range.between(
                                Bound.exclusive(Value.of(new BigDecimal("0"))),
                                Bound.inclusive(Value.of(new BigDecimal("700.5"))))).build()),
                        List.of("Human", "Individual"), true, Optional.empty(),
                        List.of(new RuleReference("person-rule", 2)),
                        List.of(new Check("email-set", "has(self.email)", Optional.of("A person has an email.")),
                                new Check("age-known", "!has(self.age) || self.age >= 0", Optional.empty()))));
        assertThat(definition.relationshipTypes()).containsExactly(
                new RelationshipTypeDefinition("Knows", "Person", "Person", Multiplicity.ZERO_OR_MORE,
                        Multiplicity.ZERO_OR_MORE, "knownBy", List.of("knownTo"), RelationshipKind.ASSOCIATION, true,
                        Optional.empty(), List.of(AttributeDefinition.builder("since", DATE).build()),
                        List.of("Acquainted"), true, Optional.of("Two people know each other."),
                        List.of(new RuleReference("knows-rule", 1)),
                        List.of(new Check("since-set", "has(self.since)", Optional.of("Since when?")))),
                new RelationshipTypeDefinition("CloseTo", "Person", "Person", Multiplicity.ZERO_OR_ONE,
                        Multiplicity.ONE_OR_MORE, "closeTo", List.of(), RelationshipKind.COMPOSITION, false, true,
                        Optional.of("Knows"), List.of(), List.of(), false, Optional.empty(), List.of(), List.of()));
    }

    @Test
    void typesAndAttributesKeepTheOrderOfTheDocument() {
        SchemaDefinition definition = valid(document(
                "entityTypes:",
                "  Zebra: { attributes: { z: { type: int }, a: { type: int }, m: { type: int } } }",
                "  Apple: {}",
                "  Mango: {}")).definition();

        assertThat(definition.entityTypes()).map(EntityTypeDefinition::name).containsExactly("Zebra", "Apple", "Mango");
        assertThat(definition.entityTypes().getFirst().attributes()).map(AttributeDefinition::name)
                .containsExactly("z", "a", "m");
    }

    @Test
    void sectionsMayComeInAnyOrder() {
        SchemaDefinition definition = valid("""
                relationshipTypes:
                  R: { source: A, target: A, targetsPerSource: "0..*", sourcesPerTarget: "0..*", inverseName: back }
                entityTypes:
                  A: {}
                origin: { name: any-order, version: "1" }
                traits:
                  T: {}
                cairn: 1
                """).definition();

        assertThat(definition.entityTypes()).hasSize(1);
        assertThat(definition.traits()).hasSize(1);
        assertThat(definition.relationshipTypes()).hasSize(1);
    }

    @Test
    void jsonIsReadLikeYaml() {
        SchemaDocument fromYaml = valid("""
                cairn: 1
                origin: { name: json-or-yaml, version: "1.0.0" }
                entityTypes:
                  Account:
                    attributes:
                      balance: { type: decimal, required: true, range: { atLeast: 0, atMost: 1000000.50 } }
                      currency: { type: string, allowedValues: [EUR, USD] }
                """);
        SchemaDocument fromJson = valid("""
                {
                  "cairn": 1,
                  "origin": { "name": "json-or-yaml", "version": "1.0.0" },
                  "entityTypes": {
                    "Account": {
                      "attributes": {
                        "balance": { "type": "decimal", "required": true, "range": { "atLeast": 0, "atMost": 1000000.50 } },
                        "currency": { "type": "string", "allowedValues": ["EUR", "USD"] }
                      }
                    }
                  }
                }
                """);

        assertThat(fromJson.origin()).isEqualTo(fromYaml.origin());
        assertThat(fromJson.definition()).isEqualTo(fromYaml.definition());
        assertThat(fromJson.definition().entityTypes().getFirst().attributes().getFirst().range()).contains(Range.between(
                Bound.inclusive(Value.of(new BigDecimal("0"))), Bound.inclusive(Value.of(new BigDecimal("1000000.50")))));
    }

    @Test
    void blockScalarsGiveMultilineDescriptions() {
        SchemaDefinition definition = valid(document(
                "entityTypes:",
                "  Party:",
                "    description: |",
                "      Anybody that can own",
                "      or be owned.",
                "  Person:",
                "    description: >-",
                "      One human",
                "      being.")).definition();

        assertThat(definition.entityTypes().get(0).description()).contains("Anybody that can own\nor be owned.\n");
        assertThat(definition.entityTypes().get(1).description()).contains("One human being.");
    }

    @Test
    void stringsThatLookLikeOtherTypesNeedQuotes() {
        assertThat(valid(document("entityTypes:", "  Party: { description: \"1.10\" }")).definition().entityTypes()
                .getFirst().description()).contains("1.10");
        assertThat(valid(document("entityTypes:", "  Party: { description: 'true' }")).definition().entityTypes()
                .getFirst().description()).contains("true");
        assertThat(valid(document("entityTypes:", "  Party: { description: no }")).definition().entityTypes()
                .getFirst().description()).contains("no");

        assertThat(problem(document("entityTypes:", "  Party: { description: 1.10 }")).code())
                .isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(problem(document("entityTypes:", "  Party: { description: true }")).code())
                .isEqualTo(ProblemCode.INVALID_VALUE);
    }

    @Test
    void windowsLineEndingsCountAsOneLineBreak() {
        SchemaDocument document = valid("cairn: 1\r\norigin: { name: crlf, version: \"1\" }\r\nentityTypes:\r\n"
                + "  Party: {}\r\n  Person: {}\r\n");

        assertThat(document.locations().of("Person").line()).isEqualTo(5);
        assertThat(document.locations().of("Person").column()).isEqualTo(3);
    }

    @Test
    void decimalsKeepTheirScale() {
        SchemaDefinition definition = valid(document(
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      balance: { type: decimal, range: { atLeast: 0.10, atMost: 100 }, examples: [1.50, 2, 0.1234567890123456789012345] }"))
                .definition();

        AttributeDefinition balance = definition.entityTypes().getFirst().attributes().getFirst();
        assertThat(balance.range()).contains(Range.between(
                Bound.inclusive(Value.of(new BigDecimal("0.10"))), Bound.inclusive(Value.of(new BigDecimal("100")))));
        assertThat(balance.examples()).containsExactly(Value.of(new BigDecimal("1.50")),
                Value.of(new BigDecimal("2")), Value.of(new BigDecimal("0.1234567890123456789012345")));
        assertThat(DECIMAL).isEqualTo(balance.valueType());
    }
}
