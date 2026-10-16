package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.assertProblem;
import static io.github.enricoag1982.cairn.documents.Documents.document;
import static io.github.enricoag1982.cairn.documents.Documents.problem;
import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.SchemaRegistry;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The classification of attributes in schema documents (capability {@code schema-documents}, design D1 of
 * {@code add-attribute-access-control}): reading in traits, entity types and relationship types, the problems of reading,
 * writing after {@code deprecated} and only where there is one, and locating the problems of registering a malformed one.
 */
class SchemaDocumentClassificationTest {

    private final SchemaRegistry registry = SchemaRegistries.inMemory(InstantSource.system());

    private static String model() {
        return document(
                "traits:",
                "  Named:",
                "    attributes:",
                "      name: { type: string, required: true, classification: names }",
                "entityTypes:",
                "  Account:",
                "    attributes:",
                "      balance: { type: decimal, deprecated: true, classification: finance }",
                "      number: { type: string }",
                "relationshipTypes:",
                "  Owns:",
                "    source: Account",
                "    target: Account",
                "    targetsPerSource: \"0..*\"",
                "    sourcesPerTarget: \"0..*\"",
                "    inverseName: ownedBy",
                "    attributes:",
                "      share: { type: decimal, classification: ownership }");
    }

    @Test
    void attributesOfTraitsEntityTypesAndRelationshipTypesCanBeClassified() {
        SchemaDocument document = valid(model());

        assertThat(document.definition().traits().getFirst().attributes().getFirst().classification())
                .hasValue("names");
        assertThat(document.definition().entityTypes().getFirst().attributes()).extracting(
                AttributeDefinition::name, AttributeDefinition::classification).containsExactly(
                        tuple("balance", Optional.of("finance")),
                        tuple("number", Optional.empty()));
        assertThat(document.definition().relationshipTypes().getFirst().attributes().getFirst().classification())
                .hasValue("ownership");
    }

    @Test
    void aClassificationWrittenAsJsonIsReadTheSame() {
        SchemaDocument document = valid("{\"cairn\": 1, \"origin\": {\"name\": \"m\", \"version\": \"1\"}, "
                + "\"entityTypes\": {\"Account\": {\"attributes\": {\"balance\": {\"type\": \"decimal\", "
                + "\"classification\": \"finance\"}}}}}");

        assertThat(document.definition().entityTypes().getFirst().attributes().getFirst().classification())
                .hasValue("finance");
    }

    @Test
    void theClassificationMustBeAString() {
        Problem number = problem(document("entityTypes:", "  Account:", "    attributes:",
                "      balance: { type: decimal, classification: 5 }"));
        Problem list = problem(document("entityTypes:", "  Account:", "    attributes:",
                "      balance: { type: decimal, classification: [finance] }"));

        assertProblem(number, ProblemCode.INVALID_VALUE, "Account.balance",
                "/entityTypes/Account/attributes/balance/classification", 6, 49);
        assertThat(number.expected()).contains("a string");
        assertThat(list.code()).isEqualTo(ProblemCode.INVALID_VALUE);
        assertThat(list.path()).isEqualTo("Account.balance");
    }

    @Test
    void aMisspelledKeyHasAHintThatNamesClassification() {
        Problem problem = problem(document("entityTypes:", "  Account:", "    attributes:",
                "      balance: { type: decimal, clasification: finance }"));

        assertThat(problem.code()).isEqualTo(ProblemCode.UNKNOWN_KEY);
        assertThat(problem.hint()).hasValueSatisfying(hint -> assertThat(hint).contains("classification"));
    }

    @Test
    void theWrittenDocumentHasTheClassificationAfterDeprecatedAndOnlyWhereThereIsOne() {
        SchemaDocument document = valid(model());

        String yaml = SchemaDocuments.writeYaml(document.origin(), document.definition());
        String json = SchemaDocuments.writeJson(document.origin(), document.definition());

        for (String text : List.of(yaml, json)) {
            Object tree = DocumentTrees.load(text);
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Account", "attributes", "balance"))
                    .containsExactly("type", "deprecated", "classification");
            assertThat(DocumentTrees.keysAt(tree, "entityTypes", "Account", "attributes", "number"))
                    .containsExactly("type");
            assertThat(DocumentTrees.at(tree, "traits", "Named", "attributes", "name", "classification"))
                    .isEqualTo("names");
            assertThat(DocumentTrees.at(tree, "relationshipTypes", "Owns", "attributes", "share", "classification"))
                    .isEqualTo("ownership");
        }
        assertThat(SchemaDocuments.read(yaml)).isInstanceOf(DocumentReadResult.Read.class);
        assertThat(valid(yaml).definition()).isEqualTo(document.definition());
        assertThat(valid(json).definition()).isEqualTo(document.definition());
    }

    @Test
    void aMalformedClassificationIsReportedByTheRegistryAtTheAttributeInTheDocument() {
        SchemaDocument document = valid(document("entityTypes:", "  Account:", "    attributes:",
                "      number: { type: string }",
                "      balance: { type: decimal, classification: Finance Data }"));

        List<Problem> problems = document.locate(registry.validate(document.origin(), document.definition()));

        assertThat(problems).singleElement().satisfies(found -> {
            assertThat(found.code()).isEqualTo(ProblemCode.INVALID_CLASSIFICATION);
            assertThat(found.path()).isEqualTo("Account.balance");
            assertThat(found.location()).hasValueSatisfying(location -> {
                assertThat(location.pointer()).isEqualTo("/entityTypes/Account/attributes/balance");
                assertThat(location.line()).isEqualTo(7);
            });
        });
    }
}
