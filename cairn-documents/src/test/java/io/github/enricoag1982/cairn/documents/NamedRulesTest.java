package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.RuleReference;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The rules that documents name and the type each is for (design D5 of {@code add-java-type-generation}). */
class NamedRulesTest {

    private static String rule(String name, int version) {
        return "{ name: " + name + ", version: " + version + " }";
    }

    private static SchemaDocument entityTypes(String origin, String... typesAndRules) {
        StringBuilder text = new StringBuilder("cairn: 1\norigin: { name: " + origin + ", version: \"1.0.0\" }\n"
                + "entityTypes:\n");
        for (int i = 0; i < typesAndRules.length; i += 2) {
            text.append("  ").append(typesAndRules[i]).append(":\n    rules:\n      - ").append(typesAndRules[i + 1])
                    .append('\n');
        }
        return Documents.valid(text.toString());
    }

    private static List<NamedRules.Named> in(SchemaDocument... documents) {
        return NamedRules.in(List.of(documents), Set.of());
    }

    @Test
    void aRuleIsForTheTypeThatNamesIt() {
        List<NamedRules.Named> named = in(entityTypes("crm", "Lead", rule("lead-score", 2)));

        assertThat(named).containsExactly(new NamedRules.Named(new RuleReference("lead-score", 2), "Lead", 0));
    }

    @Test
    void theRulesAreInTheOrderInWhichTheyAreFirstNamedAndEachIsFoundOnce() {
        SchemaDocument one = entityTypes("one", "Lead", rule("b-rule", 1), "Customer", rule("a-rule", 1));
        SchemaDocument two = entityTypes("two", "Account", rule("a-rule", 1), "Other", rule("c-rule", 1));

        List<NamedRules.Named> named = in(one, two);

        assertThat(named).extracting(rule -> rule.rule().name()).containsExactly("b-rule", "a-rule", "c-rule");
        assertThat(named).extracting(NamedRules.Named::appliesTo).containsExactly("Lead", "Customer", "Other");
        assertThat(named).extracting(NamedRules.Named::document).containsExactly(0, 0, 1);
    }

    @Test
    void aRuleThatIsProvidedIsLeftOutInThatVersionOnly() {
        SchemaDocument document = entityTypes("crm", "Lead", rule("lead-has-source", 1), "Customer",
                rule("lead-has-source", 2));

        List<NamedRules.Named> named = NamedRules.in(List.of(document), Set.of(new RuleReference("lead-has-source", 1)));

        assertThat(named).containsExactly(
                new NamedRules.Named(new RuleReference("lead-has-source", 2), "Customer", 0));
    }

    @Test
    void aReferenceWithAMalformedNameOrVersionIsLeftToRegistrationToReport() {
        SchemaDocument document = entityTypes("crm", "Lead", rule("Lead_Score", 1), "Customer",
                rule("customer-score", 0), "Account", rule("fine", 1));

        assertThat(in(document)).extracting(rule -> rule.rule().name()).containsExactly("fine");
    }

    @Test
    void documentsThatNameNoRuleHaveNone() {
        SchemaDocument document = Documents.valid("cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\n"
                + "entityTypes:\n  Lead: {}\n");

        assertThat(in(document)).isEmpty();
        assertThat(NamedRules.in(List.of(), Set.of())).isEmpty();
    }

    @Test
    void theOrderOfTheDocumentHoldsAcrossTheSectionsOfTheDocument() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                relationshipTypes:
                  Knows:
                    source: Lead
                    target: Lead
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: knownBy
                    rules:
                      - { name: shared, version: 1 }
                entityTypes:
                  Lead:
                    rules:
                      - { name: shared, version: 1 }
                """);

        assertThat(in(document)).singleElement().satisfies(rule -> assertThat(rule.appliesTo()).isEqualTo("Knows"));
    }

    @Test
    void aRuleIsForTheFirstTypeThatNamesItInTheOrderOfTheDocument() {
        SchemaDocument document = entityTypes("crm", "Zeta", rule("shared", 1), "Alpha", rule("shared", 1));

        assertThat(in(document)).singleElement().satisfies(rule -> assertThat(rule.appliesTo()).isEqualTo("Zeta"));
    }

    // ------------------------------------------------------------------ the type a rule is for

    @Test
    void aRuleThatTypesWithACommonSupertypeNameIsForTheSupertype() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Party: { abstract: true }
                  Person:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                  Organization:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(document)).singleElement().satisfies(rule -> {
            assertThat(rule.rule()).isEqualTo(new RuleReference("lead-score", 1));
            assertThat(rule.appliesTo()).isEqualTo("Party");
        });
    }

    @Test
    void theClosestCommonSupertypeIsFoundThroughSeveralLevelsAndAcrossDocuments() {
        SchemaDocument people = Documents.valid("""
                cairn: 1
                origin: { name: people, version: "1.0.0" }
                entityTypes:
                  Student:
                    supertype: Person
                    rules:
                      - { name: lead-score, version: 1 }
                  Employee:
                    supertype: Person
                    rules:
                      - { name: lead-score, version: 1 }
                  Person:
                    supertype: Party
                """);
        SchemaDocument parties = Documents.valid("""
                cairn: 1
                origin: { name: parties, version: "1.0.0" }
                entityTypes:
                  Party: {}
                  Organization:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(people)).extracting(NamedRules.Named::appliesTo).containsExactly("Person");
        assertThat(in(people, parties)).extracting(NamedRules.Named::appliesTo).containsExactly("Party");
    }

    @Test
    void theSupertypesAreFoundWhateverTheOrderOfTheDocuments() {
        SchemaDocument people = Documents.valid("""
                cairn: 1
                origin: { name: people, version: "1.0.0" }
                entityTypes:
                  Person:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);
        SchemaDocument parties = Documents.valid("""
                cairn: 1
                origin: { name: parties, version: "1.0.0" }
                entityTypes:
                  Party: {}
                  Organization:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(people, parties)).extracting(NamedRules.Named::appliesTo).containsExactly("Party");
        assertThat(in(parties, people)).extracting(NamedRules.Named::appliesTo).containsExactly("Party");
    }

    @Test
    void typesWithoutACommonSupertypeKeepTheFirstInTheOrderOfTheDocument() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Party: {}
                  Lead:
                    rules:
                      - { name: lead-score, version: 1 }
                  Person:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(document)).extracting(NamedRules.Named::appliesTo).containsExactly("Lead");
    }

    @Test
    void typesOfDifferentKindsKeepTheFirstEvenIfTheirNamesLookRelated() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Party: {}
                  Person:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                relationshipTypes:
                  Knows:
                    source: Person
                    target: Person
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: knownBy
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(document)).extracting(NamedRules.Named::appliesTo).containsExactly("Person");
    }

    @Test
    void relationshipTypesHaveSupertypesOfTheirOwnKind() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                relationshipTypes:
                  Link:
                    source: Person
                    target: Person
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: linkedFrom
                  Knows:
                    supertype: Link
                    source: Person
                    target: Person
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: knownBy
                    rules:
                      - { name: lead-score, version: 1 }
                  Employs:
                    supertype: Link
                    source: Person
                    target: Person
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: employedBy
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(document)).extracting(NamedRules.Named::appliesTo).containsExactly("Link");
    }

    @Test
    void aCycleOfSupertypesDoesNotLoopAndKeepsTheFirstType() {
        SchemaDocument document = Documents.valid("""
                cairn: 1
                origin: { name: crm, version: "1.0.0" }
                entityTypes:
                  Alpha:
                    supertype: Beta
                    rules:
                      - { name: lead-score, version: 1 }
                  Beta:
                    supertype: Alpha
                  Gamma:
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        assertThat(in(document)).extracting(NamedRules.Named::appliesTo).containsExactly("Alpha");
    }
}
