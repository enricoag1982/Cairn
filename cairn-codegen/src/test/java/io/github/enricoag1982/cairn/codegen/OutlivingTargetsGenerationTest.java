package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Javadoc of a generated relationship type says that its relationships may outlive their targets, and that navigation
 * then returns the target's last version (design D1 of {@code add-relationships-that-outlive-their-targets}).
 */
class OutlivingTargetsGenerationTest {

    private static final String DOCUMENT = """
            cairn: 1
            origin: { name: accounts, version: "1.0.0" }
            entityTypes:
              Person: {}
              Account: {}
            relationshipTypes:
              OpenedBy:
                description: The person who opened an account.
                source: Account
                target: Person
                targetsPerSource: "0..1"
                sourcesPerTarget: "0..*"
                inverseName: opened
                outlivesTarget: true
              HeldBy:
                source: Account
                target: Person
                targetsPerSource: "1..1"
                sourcesPerTarget: "0..*"
                inverseName: holds
            """;

    @TempDir
    Path directory;

    @Test
    void aRelationshipTypeThatOutlivesItsTargetsSaysSoInItsJavadoc() throws IOException {
        Generation.Generated generated = generate(directory, DOCUMENT);

        String openedBy = text(generated, "OpenedBy");
        assertThat(openedBy).contains("The person who opened an account.")
                .contains("Its relationships may outlive their targets")
                .contains("navigation from the source then returns the target's last version");
        assertThat(text(generated, "HeldBy")).doesNotContain("outlive");
        assertThat(compile(generated, directory).diagnostics()).isEmpty();
    }
}
