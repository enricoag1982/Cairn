package io.github.enricoag1982.cairn.agents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The rules that the server declares from the documents that name them (design D7 of {@code add-agent-tools}). */
class DeclaredRulesTest {

    private static final Path CRM = Path.of("/models/crm.cairn.yaml");

    private static SchemaDocument read(String text) {
        DocumentReadResult result = SchemaDocuments.read(text);
        assertThat(result).as(text).isInstanceOf(DocumentReadResult.Read.class);
        return ((DocumentReadResult.Read) result).document();
    }

    private static String rule(String name, int version) {
        return "{ name: " + name + ", version: " + version + " }";
    }

    private static SchemaDocument entityTypes(String... typesAndRules) {
        StringBuilder text = new StringBuilder("cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\nentityTypes:\n");
        for (int i = 0; i < typesAndRules.length; i += 2) {
            text.append("  ").append(typesAndRules[i]).append(":\n    rules:\n      - ").append(typesAndRules[i + 1])
                    .append('\n');
        }
        return read(text.toString());
    }

    private static List<RuleDescriptor> declared(SchemaDocument document, Set<RuleReference> provided) {
        return DeclaredRules.namedBy(List.of(CRM), List.of(document), provided);
    }

    @Test
    void aRuleThatADocumentNamesIsDescribedFromTheDocument() {
        SchemaDocument document = entityTypes("Lead", rule("lead-score", 2));

        assertThat(declared(document, Set.of())).containsExactly(new RuleDescriptor("lead-score", 2,
                "Named by the schema document crm.cairn.yaml; the server has no code for this rule.", "Lead",
                List.of()));
    }

    @Test
    void aRuleIsAppliedToTheFirstTypeThatNamesItInTheOrderOfTheDocument() {
        SchemaDocument document = entityTypes("Zeta", rule("shared", 1), "Alpha", rule("shared", 1));

        assertThat(declared(document, Set.of())).singleElement().satisfies(rule -> {
            assertThat(rule.appliesTo()).isEqualTo("Zeta");
            assertThat(rule.name()).isEqualTo("shared");
        });
    }

    @Test
    void theOrderOfTheDocumentHoldsAcrossTheSectionsOfTheDocument() {
        SchemaDocument document = read("""
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

        assertThat(declared(document, Set.of())).singleElement()
                .satisfies(rule -> assertThat(rule.appliesTo()).isEqualTo("Knows"));
    }

    @Test
    void theRulesAreInTheOrderInWhichTheyAreFirstNamedAndEachIsDeclaredOnce() {
        SchemaDocument document = entityTypes("Lead", rule("b-rule", 1), "Customer", rule("a-rule", 1));
        SchemaDocument again = entityTypes("Account", rule("a-rule", 1), "Other", rule("c-rule", 1));

        List<RuleDescriptor> declared = DeclaredRules.namedBy(List.of(Path.of("one.yaml"), Path.of("two.yaml")),
                List.of(document, again), Set.of());

        assertThat(declared).extracting(RuleDescriptor::name).containsExactly("b-rule", "a-rule", "c-rule");
        assertThat(declared).extracting(RuleDescriptor::appliesTo).containsExactly("Lead", "Customer", "Other");
        assertThat(declared.get(1).description()).contains("one.yaml").doesNotContain("two.yaml");
        assertThat(declared.get(2).description()).contains("two.yaml");
    }

    @Test
    void aRuleTheServerHasTheCodeOfIsNotDeclaredInThatVersionOnly() {
        SchemaDocument document = entityTypes("Lead", rule("lead-has-source", 1), "Customer", rule("lead-has-source", 2));

        assertThat(declared(document, Set.of(new RuleReference("lead-has-source", 1))))
                .singleElement().satisfies(rule -> {
                    assertThat(rule.name()).isEqualTo("lead-has-source");
                    assertThat(rule.version()).isEqualTo(2);
                    assertThat(rule.appliesTo()).isEqualTo("Customer");
                });
    }

    @Test
    void aReferenceWithAMalformedNameOrVersionIsLeftToRegistrationToReport() {
        SchemaDocument document = entityTypes("Lead", rule("Lead_Score", 1), "Customer", rule("customer-score", 0),
                "Account", rule("fine", 1));

        assertThat(declared(document, Set.of())).extracting(RuleDescriptor::name).containsExactly("fine");
    }

    @Test
    void aDocumentThatNamesNoRuleDeclaresNone() {
        SchemaDocument document = read("cairn: 1\norigin: { name: crm, version: \"1.0.0\" }\nentityTypes:\n  Lead: {}\n");

        assertThat(declared(document, Set.of())).isEmpty();
        assertThat(DeclaredRules.namedBy(List.of(), List.of(), Set.of())).isEmpty();
    }

    // ------------------------------------------------------------------ the type a rule is for

    private static final String PARTIES = """
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
            """;

    @Test
    void aRuleThatTypesWithACommonSupertypeNameIsDeclaredForTheSupertype() {
        assertThat(declared(read(PARTIES), Set.of())).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("lead-score");
            assertThat(rule.appliesTo()).isEqualTo("Party");
        });
    }

    @Test
    void theClosestCommonSupertypeIsFoundThroughSeveralLevelsAndAcrossDocuments() {
        SchemaDocument people = read("""
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
        SchemaDocument parties = read("""
                cairn: 1
                origin: { name: parties, version: "1.0.0" }
                entityTypes:
                  Party: {}
                  Organization:
                    supertype: Party
                    rules:
                      - { name: lead-score, version: 1 }
                """);

        List<RuleDescriptor> students = DeclaredRules.namedBy(List.of(Path.of("a.yaml")), List.of(people), Set.of());
        List<RuleDescriptor> both = DeclaredRules.namedBy(List.of(Path.of("a.yaml"), Path.of("b.yaml")),
                List.of(people, parties), Set.of());

        assertThat(students).extracting(RuleDescriptor::appliesTo).containsExactly("Person");
        assertThat(both).extracting(RuleDescriptor::appliesTo).containsExactly("Party");
        assertThat(both.getFirst().description()).contains("a.yaml");
    }

    @Test
    void typesWithoutACommonSupertypeKeepTheFirstInTheOrderOfTheDocument() {
        SchemaDocument document = read("""
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

        assertThat(declared(document, Set.of())).extracting(RuleDescriptor::appliesTo).containsExactly("Lead");
    }

    @Test
    void typesOfDifferentKindsKeepTheFirstEvenIfTheirNamesLookRelated() {
        SchemaDocument document = read("""
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

        assertThat(declared(document, Set.of())).extracting(RuleDescriptor::appliesTo).containsExactly("Person");
    }

    @Test
    void relationshipTypesHaveSupertypesOfTheirOwnKind() {
        SchemaDocument document = read("""
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

        assertThat(declared(document, Set.of())).extracting(RuleDescriptor::appliesTo).containsExactly("Link");
    }

    @Test
    void aCycleOfSupertypesDoesNotLoopAndKeepsTheFirstType() {
        SchemaDocument document = read("""
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

        assertThat(declared(document, Set.of())).extracting(RuleDescriptor::appliesTo).containsExactly("Alpha");
    }
}
