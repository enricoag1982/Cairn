package io.github.enricoag1982.cairn.documents;

import static io.github.enricoag1982.cairn.documents.Documents.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import io.github.enricoag1982.cairn.schema.Origin;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code docs/reference/reference-model.cairn.yaml} is the reference domain model of
 * {@link ReferenceModel}, which {@code ReferenceModelTest} checks against {@code reference-domain.md}.
 */
class ReferenceModelDocumentTest {

    @Test
    void theDocumentIsTheReferenceModel() throws IOException {
        SchemaDocument document = valid(Documents.referenceModelText());

        assertThat(document.origin()).isEqualTo(new Origin("reference-model", "1.0.0"));
        assertThat(document.origin()).isEqualTo(ReferenceModel.origin("1.0.0"));
        assertThat(document.definition().traits()).isEqualTo(ReferenceModel.definition().traits());
        assertThat(document.definition().entityTypes()).isEqualTo(ReferenceModel.definition().entityTypes());
        assertThat(document.definition().relationshipTypes()).isEqualTo(ReferenceModel.definition().relationshipTypes());
        assertThat(document.definition()).isEqualTo(ReferenceModel.definition());
    }

    @Test
    void elementsAreLocatedWhereTheyAreDefined() throws IOException {
        SchemaDocument document = valid(Documents.referenceModelText());
        String[] lines = Documents.referenceModelText().split("\n", -1);

        assertThat(document.locations().of("Organization"))
                .isEqualTo(new DocumentLocation("/entityTypes/Organization", 21, 3));
        assertThat(lines[20]).isEqualTo("  Organization:");
        assertThat(document.locations().of("Account.balance"))
                .isEqualTo(new DocumentLocation("/entityTypes/Account/attributes/balance", 37, 7));
        assertThat(lines[36]).startsWith("      balance:");
        assertThat(document.locations().of("Owns.ownedBy"))
                .isEqualTo(new DocumentLocation("/relationshipTypes/Owns/inverseName", 48, 5));
        assertThat(document.locations().of("@origin")).isEqualTo(new DocumentLocation("/origin", 4, 1));
    }
}
