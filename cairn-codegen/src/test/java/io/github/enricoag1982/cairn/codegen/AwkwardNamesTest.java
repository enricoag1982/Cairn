package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.byPath;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Names that Java would not accept, or that clash, in documents (design D4 of {@code add-java-type-generation}): the
 * generated source must compile with every lint warning and Javadoc problem an error, whatever the names.
 */
class AwkwardNamesTest {

    @TempDir
    Path directory;

    private static List<String> methods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).sorted().toList();
    }

    @Test
    void typesNamedLikeLibraryTypesAndLikeNestedClassesCompile() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: awkward, version: "1.0.0" }
                entityTypes:
                  Map: { attributes: { key: { type: string, required: true } } }
                  Optional: { attributes: { present: { type: boolean } } }
                  String: { attributes: { text: { type: string } } }
                  Value: { attributes: { amount: { type: decimal } } }
                  Write: { attributes: { at: { type: instant } } }
                  Ref: { attributes: { day: { type: date } } }
                  List: { attributes: { n: { type: int } } }
                  Set: { attributes: { n: { type: long } } }
                  Instant: {}
                  LocalDate: {}
                  BigDecimal: {}
                  EntityState: {}
                  Snapshot: { attributes: { name: { type: string } } }
                  Create: {}
                  Patch: {}
                  Replace: {}
                  Relate: {}
                  Override: {}
                  Deprecated: {}
                  SuppressWarnings: {}
                  Object: {}
                  Integer: {}
                  Boolean: {}
                  Long: {}
                  LinkedHashMap: {}
                  IllegalArgumentException: {}
                  TypedValues: {}
                  TypedEntity: {}
                  EntityRule: {}
                  RuleContext: {}
                  class: {}
                  record: { attributes: { var: { type: int } } }
                  yield: {}
                relationshipTypes:
                  Holds:
                    source: Map
                    target: String
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: heldBy
                  Relate_:
                    source: Snapshot
                    target: Create
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: relatedBy
                  Patch_:
                    supertype: Relate_
                    source: Snapshot
                    target: Create
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: patchedBy
                  TypedRelationship:
                    source: Relate
                    target: Patch
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: typed
                """);

        assertThat(byPath(generated).keySet()).contains("com/example/generated/class_.java",
                "com/example/generated/record_.java", "com/example/generated/yield_.java",
                "com/example/generated/Map.java", "com/example/generated/Snapshot.java");
        assertThat(text(generated, "Snapshot")).contains("record Snapshot_(").contains("static Snapshot_ of(")
                .contains("interface Snapshot extends");
        assertThat(text(generated, "Create")).contains("final class Create_ {").contains("static Create_ create()");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(loader.loadClass(PACKAGE + ".class_").isInterface()).isTrue();
            assertThat(methods(loader.loadClass(PACKAGE + ".record_"))).contains("var_");
        }
    }

    @Test
    void attributesNamedLikeMembersThatGeneratedCodeAddsGetASuffix() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: members, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      id: { type: string }
                      entityState: { type: string }
                      from: { type: string }
                      create: { type: string }
                      patch: { type: string }
                      replace: { type: string }
                      as: { type: string }
                      check: { type: string }
                      effective: { type: string }
                      effectiveFrom: { type: string }
                      source: { type: string }
                      target: { type: string }
                      relate: { type: string }
                      toString: { type: string }
                      hashCode: { type: string }
                      getClass: { type: string }
                      default: { type: string }
                      default_: { type: string }
                      Value: { type: string }
                      Optional: { type: string }
                      TypedValues: { type: string }
                      Write: { type: string }
                relationshipTypes:
                  Link:
                    source: Thing
                    target: Thing
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: linkedFrom
                    attributes:
                      id: { type: string }
                      source: { type: string }
                      target: { type: string }
                      relationshipState: { type: string }
                      entityState: { type: string }
                      relate: { type: string }
                      create: { type: string }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(methods(loader.loadClass(PACKAGE + ".Thing"))).contains("id_", "entityState_", "from_",
                    "create_", "patch_", "replace_", "as_", "check_", "effective_", "effectiveFrom_", "source",
                    "target", "relate", "toString_", "hashCode_", "getClass_", "default__", "default_", "Value_",
                    "Optional_", "TypedValues_", "Write_", "from", "create", "patch", "replace");
            assertThat(methods(loader.loadClass(PACKAGE + ".Link"))).contains("id_", "source_", "target_",
                    "relationshipState_", "entityState", "relate_", "create", "from", "relate", "patch", "replace");
        }
    }

    @Test
    void thePackageOfTheGeneratedTypesCanBeAnyLegalPackageName() throws Exception {
        Path file = GeneratedSource.document(directory, "model.cairn.yaml", """
                cairn: 1
                origin: { name: names, version: "1.0.0" }
                entityTypes:
                  Thing: {}
                """);

        Generation.Generated generated = (Generation.Generated) JavaTypes.generate(List.of(file), "a.record.b_c.$d");

        assertThat(generated.files()).extracting(GeneratedFile::path).containsExactly("a/record/b_c/$d/Thing.java");
        assertThat(generated.files().getFirst().text()).contains("\npackage a.record.b_c.$d;\n");
    }

    @Test
    void descriptionsWithMarkupAndSpecialCharactersAreSafeInComments() throws IOException {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: markup, version: "1.0.0" }
                traits:
                  Tagged:
                    description: "A trait: <b>bold</b> & {@code oops} and */ and \\\\u0041 and \\\\u000a, caf\\u00e9, \\U0001F600."
                    attributes:
                      tag:
                        type: string
                        required: true
                        description: |
                          Line one
                          line two.

                          Line three with @param and @return.
                        pattern: "[a-z]*<>&"
                entityTypes:
                  Thing:
                    traits: [Tagged]
                    description: "<p>Starts with markup"
                    attributes:
                      note:
                        type: string
                        allowedValues: ["a<b", "c&d", "*/", "\\\\u0041"]
                        minLength: 1
                        maxLength: 9
                      size:
                        type: int
                        range: { atLeast: 1 }
                        unique: true
                      blank: { type: string, description: "   " }
                """);

        compile(generated, directory);
        assertThat(generated.files()).allSatisfy(file -> assertThat(file.text()).matches(text -> text.chars()
                .allMatch(c -> c < 128), "only ASCII characters"));
        assertThat(text(generated, "Tagged")).contains("{@code oops}".replace("@", "&#64;"))
                .contains("*&#47;").contains("caf&#233;").contains("&#128512;");
    }

    @Test
    void deprecatedTypesAndAttributesAreDeprecatedInJavaAndGeneratedCodeDoesNotWarnAboutThem() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: deprecations, version: "1.0.0" }
                traits:
                  OldTrait:
                    deprecated: true
                    attributes:
                      legacy: { type: string, deprecated: true }
                entityTypes:
                  Old:
                    deprecated: true
                    traits: [OldTrait]
                    attributes:
                      number: { type: int, deprecated: true }
                  Newer:
                    supertype: Old
                    attributes:
                      fresh: { type: int }
                relationshipTypes:
                  Replaces:
                    deprecated: true
                    source: Newer
                    target: Old
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: replacedBy
                    attributes:
                      since: { type: date, deprecated: true }
                  Succeeds:
                    supertype: Replaces
                    source: Newer
                    target: Newer
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: succeededBy
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> old = loader.loadClass(PACKAGE + ".Old");
            assertThat(old.isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(old.getMethod("number").isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(loader.loadClass(PACKAGE + ".Newer").isAnnotationPresent(Deprecated.class)).isFalse();
            assertThat(loader.loadClass(PACKAGE + ".Newer").getMethod("fresh").isAnnotationPresent(Deprecated.class))
                    .isFalse();
            assertThat(loader.loadClass(PACKAGE + ".OldTrait").getMethod("legacy")
                    .isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(loader.loadClass(PACKAGE + ".Replaces").getMethod("since")
                    .isAnnotationPresent(Deprecated.class)).isTrue();
        }
    }

    @Test
    void ruleClassesGetNamesThatDoNotClashWithTypesOrEachOther() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: rules, version: "1.0.0" }
                entityTypes:
                  Thing:
                    rules:
                      - { name: a-b, version: 1 }
                      - { name: a--b, version: 1 }
                      - { name: x1, version: 12 }
                  Other:
                    rules:
                      - { name: a-b, version: 2 }
                  ABRuleV1: {}
                """);

        assertThat(byPath(generated).keySet()).containsExactly("com/example/generated/ABRuleV1.java",
                "com/example/generated/ABRuleV1_.java", "com/example/generated/ABRuleV1__.java",
                "com/example/generated/ABRuleV2.java", "com/example/generated/Other.java", "com/example/generated/Thing.java",
                "com/example/generated/X1RuleV12.java");
        assertThat(text(generated, "ABRuleV1_")).contains("NAME = \"a--b\"");
        assertThat(text(generated, "ABRuleV1__")).contains("NAME = \"a-b\"");
        compile(generated, directory);
    }
}
