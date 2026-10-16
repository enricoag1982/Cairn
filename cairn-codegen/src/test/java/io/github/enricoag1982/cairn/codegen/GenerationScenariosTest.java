package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.document;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.RuleContext;
import io.github.enricoag1982.cairn.schema.DocumentLocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenarios of the {@code java-types} spec about what is generated and how documents are checked: the generator is
 * run on documents, and its source is compiled in this process with every lint warning and Javadoc problem an error.
 */
class GenerationScenariosTest {

    @TempDir
    Path directory;

    private Class<?> load(URLClassLoader loader, String type) throws ClassNotFoundException {
        return loader.loadClass(PACKAGE + "." + type);
    }

    // ------------------------------------------------------------------ generated types

    @Test
    @DisplayName("Scenario: Interfaces for the reference model")
    void interfacesForTheReferenceModel() throws Exception {
        Generation.Generated generated = generate(ReferenceDomain.DOCUMENT);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> named = load(loader, "Named");
            Class<?> party = load(loader, "Party");
            Class<?> person = load(loader, "Person");
            Class<?> organization = load(loader, "Organization");
            Class<?> owns = load(loader, "Owns");

            assertThat(party.isInterface()).isTrue();
            assertThat(party.getInterfaces()).containsExactly(named);
            assertThat(person.getInterfaces()).containsExactly(party);
            assertThat(organization.getInterfaces()).containsExactly(party);
            assertThat(person.getMethod("birthDate").getGenericReturnType().getTypeName())
                    .isEqualTo("java.util.Optional<java.time.LocalDate>");
            assertThat(named.getMethod("name").getReturnType()).isEqualTo(String.class);
            assertThat(owns.getMethod("share").getReturnType()).isEqualTo(BigDecimal.class);
            assertThat(owns.getInterfaces()).extracting(Class::getSimpleName).containsExactly("TypedRelationship");
            assertThat(named.getInterfaces()).extracting(Class::getSimpleName).containsExactly("TypedEntity");
        }
    }

    @Test
    @DisplayName("Scenario: A rule that several types name")
    void aRuleThatSeveralTypesName() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: contacts, version: "1.0.0" }
                entityTypes:
                  Party:
                    abstract: true
                  Person:
                    supertype: Party
                    rules:
                      - { name: contact-known, version: 1 }
                  Organization:
                    supertype: Party
                    rules:
                      - { name: contact-known, version: 1 }
                """);

        assertThat(text(generated, "ContactKnownRuleV1"))
                .contains("protected abstract void check(Party value, RuleContext context);")
                .contains("APPLIES_TO = \"Party\"");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> rule = load(loader, "ContactKnownRuleV1");
            Method check = rule.getDeclaredMethod("check", load(loader, "Party"), RuleContext.class);
            assertThat(Modifier.isAbstract(check.getModifiers())).isTrue();
            assertThat(Modifier.isProtected(check.getModifiers())).isTrue();
            assertThat(rule.getDeclaredField("APPLIES_TO").get(null)).isEqualTo("Party");
        }
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("Scenario: An attribute named like a Java keyword")
    void anAttributeNamedLikeAJavaKeyword() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: settings, version: "1.0.0" }
                entityTypes:
                  Setting:
                    attributes:
                      default: { type: string }
                """);

        assertThat(text(generated, "Setting")).contains("Optional<String> default_();");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Method accessor = load(loader, "Setting").getMethod("default_");
            assertThat(accessor.getGenericReturnType().getTypeName()).isEqualTo("java.util.Optional<java.lang.String>");
        }
    }

    @Test
    @DisplayName("Scenario: Types named like Java library types")
    void typesNamedLikeJavaLibraryTypes() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: library, version: "1.0.0" }
                entityTypes:
                  Map:
                    attributes:
                      key: { type: string, required: true }
                  Optional:
                    attributes:
                      present: { type: boolean }
                  String:
                    attributes:
                      text: { type: string }
                      length: { type: int, required: true }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(load(loader, "Map").getMethod("key").getReturnType()).isEqualTo(String.class);
            assertThat(load(loader, "String").getMethod("length").getReturnType()).isEqualTo(int.class);
            assertThat(load(loader, "Optional").getMethod("present").getGenericReturnType().getTypeName())
                    .isEqualTo("java.util.Optional<java.lang.Boolean>");
        }
    }

    @Test
    @DisplayName("Scenario: The inverse name as a constant")
    void theInverseNameAsAConstant() throws Exception {
        Generation.Generated generated = generate(ReferenceDomain.DOCUMENT);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(load(loader, "Owns").getField("INVERSE").get(null)).isEqualTo("ownedBy");
            assertThat(load(loader, "HeldBy").getField("INVERSE").get(null)).isEqualTo("holds");
            assertThat(load(loader, "Owns").getField("TYPE").get(null)).isEqualTo("Owns");
        }
    }

    // ------------------------------------------------------------------ checking documents

    @Test
    @DisplayName("Scenario: Documents with problems")
    void documentsWithProblems() throws IOException {
        Path file = document(directory, "model.cairn.yaml", """
                cairn: 1
                origin: { name: reference-model, version: "1.0.0" }
                entityTypes:
                  Party:
                    abstract: true
                  Organization:
                    supertype: Prty
                """);

        Generation generation = JavaTypes.generate(List.of(file), PACKAGE);

        assertThat(generation).isInstanceOf(Generation.Failed.class);
        List<DocumentProblem> problems = ((Generation.Failed) generation).problems();
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.document()).isEqualTo(file);
            assertThat(problem.problem().path()).isEqualTo("Organization");
            assertThat(problem.problem().location()).contains(new DocumentLocation("/entityTypes/Organization", 6, 3));
            assertThat(problem.format()).startsWith(file + ":6:3: UNKNOWN_REFERENCE Organization: ");
        });

        Path output = directory.resolve("generated");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CairnCodegen.run(new String[] {"--package", PACKAGE, "--output", output.toString(),
                file.toString()}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true,
                        StandardCharsets.UTF_8));
        assertThat(status).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(file + ":6:3: UNKNOWN_REFERENCE Organization: ");
        assertThat(output).as("nothing is written").doesNotExist();
    }

    @Test
    @DisplayName("Scenario: Documents that depend on each other")
    void documentsThatDependOnEachOther() throws IOException {
        Path ownership = document(directory, "ownership.cairn.yaml", """
                cairn: 1
                origin: { name: ownership, version: "1.0.0" }
                relationshipTypes:
                  Owns:
                    source: Organization
                    target: Organization
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: ownedBy
                """);
        Path organizations = document(directory, "organizations.cairn.yaml", """
                cairn: 1
                origin: { name: organizations, version: "1.0.0" }
                entityTypes:
                  Organization:
                    attributes:
                      name: { type: string, required: true }
                """);

        Generation.Generated forward = generate(ownership, organizations);
        Generation.Generated backward = generate(organizations, ownership);

        assertThat(forward.files()).extracting(GeneratedFile::path)
                .containsExactly("com/example/generated/Organization.java", "com/example/generated/Owns.java");
        assertThat(backward).isEqualTo(forward);
        assertThat(text(forward, "Owns")).contains("static Relate relate(Organization source, Organization target)");
        compile(forward, directory);
    }
}
