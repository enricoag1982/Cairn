package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Derived attributes in generated Java types (capability {@code derived-attributes}, design D7 of
 * {@code add-derived-attributes}): no setter in the builders of writes, accessors, snapshot records and typed references
 * that say they are derived and give the expression. The generator checks the document with a schema registry, which
 * compiles the expressions, so these tests have the module {@code cairn-cel} on their class path.
 */
class DerivedAttributesGenerationTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final EntityId ID = new EntityId("00000000-0000-7000-8000-000000000001");

    private static final String ACCOUNT = """
            cairn: 1
            origin: { name: bank, version: "1.0.0" }
            entityTypes:
              Account:
                attributes:
                  number: { type: string, required: true }
                  currency: { type: string, required: true }
                  label:
                    type: string
                    required: true
                    derived: self.number + ' ' + self.currency
                  note: { type: string }
            """;

    @TempDir
    Path directory;

    /** The source with the lines of a Javadoc comment joined, so that a sentence the generator wrapped is in one piece. */
    private static String flat(String source) {
        return source.replaceAll("\\s*\n\\s*\\*\\s+", " ");
    }

    private static Class<?> nested(Class<?> type, String name) {
        return Arrays.stream(type.getClasses()).filter(member -> member.getSimpleName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " in " + type));
    }

    private static List<String> methods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).sorted().toList();
    }

    private static Object call(Class<?> type, String name, Object argument) throws Exception {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                return method.invoke(null, argument);
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    @DisplayName("Scenario: A derived attribute in generated Java types")
    void aDerivedAttributeInGeneratedJavaTypes() throws Exception {
        Generation.Generated generated = generate(directory, ACCOUNT);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> account = loader.loadClass(PACKAGE + ".Account");
            Class<?> snapshot = nested(account, "Snapshot");

            assertThat(methods(nested(account, "Create"))).as("the builder of creates").doesNotContain("label")
                    .contains("number", "currency", "note");
            assertThat(methods(nested(account, "Patch"))).as("the builder of patches").doesNotContain("label",
                    "clearLabel").contains("number", "currency", "note", "clearNote");
            assertThat(methods(nested(account, "Replace"))).as("the builder of replaces").doesNotContain("label")
                    .contains("number", "currency", "note");
            assertThat(Arrays.stream(snapshot.getRecordComponents()).map(RecordComponent::getName))
                    .as("the snapshot record").contains("number", "currency", "label", "note");
            assertThat(account.getMethod("label").getReturnType()).isEqualTo(String.class);
        }
    }

    @Test
    void theSnapshotReadsTheDerivedValueFromAState() throws Exception {
        Generation.Generated generated = generate(directory, ACCOUNT);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> account = loader.loadClass(PACKAGE + ".Account");
            Map<String, Value> values = new LinkedHashMap<>();
            values.put("number", Value.of("A-1"));
            values.put("currency", Value.of("EUR"));
            values.put("label", Value.of("A-1 EUR"));
            EntityState state = new EntityState(ID, "Account", values, EffectivePeriod.from(FROM), FROM, new Basis(1));

            Object read = call(account, "from", state);

            assertThat(account.getMethod("label").invoke(read)).isEqualTo("A-1 EUR");
            assertThat(account.getMethod("note").invoke(read)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void theBuildersOfWritesLeaveDerivedValuesOutOfWhatTheyBuild() throws Exception {
        Generation.Generated generated = generate(directory, ACCOUNT);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> account = loader.loadClass(PACKAGE + ".Account");
            Object builder = account.getMethod("create").invoke(null);
            builder.getClass().getMethod("number", String.class).invoke(builder, "A-1");
            builder.getClass().getMethod("currency", String.class).invoke(builder, "EUR");

            Object write = builder.getClass().getMethod("effectiveFrom", Instant.class).invoke(builder, FROM);

            assertThat(((Write.Create) write).values()).containsOnlyKeys("number", "currency");
        }
    }

    @Test
    void theAccessorTheReferenceAndTheSnapshotSayThatTheAttributeIsDerivedAndGiveTheExpression() throws Exception {
        Generation.Generated generated = generate(directory, ACCOUNT);

        String source = flat(text(generated, "Account"));

        assertThat(source).contains("Derived: the store computes the value from the other values of the same version when "
                + "a transaction commits, with the expression <code>self.number + ' ' + self.currency</code>; a write "
                + "cannot set it.");
        assertThat(source.split("Derived: the store computes", -1)).as("in the accessor and in the typed reference")
                .hasSize(3);
        assertThat(source).contains("@param label the value of <code>label</code>, which is derived from "
                + "<code>self.number + ' ' + self.currency</code>");
        assertThat(source).contains("OrderedAttributeRef<String> LABEL");
        compile(generated, directory);
    }

    @Test
    void anExpressionWithMarkupAndAnAtSignIsEscapedInTheJavadoc() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: odd, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      size: { type: int }
                      markup: { type: string, derived: "'<b>&</b> @see */'" }
                """);

        String source = flat(text(generated, "Thing"));

        assertThat(source).contains("<code>'&lt;b&gt;&amp;&lt;/b&gt; &#64;see *&#47;'</code>");
        compile(generated, directory);
    }

    @Test
    void aDerivedAttributeOfATraitAndOfARelationshipTypeIsLeftOutOfTheirBuildersToo() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: both, version: "1.0.0" }
                traits:
                  Labelled:
                    attributes:
                      name: { type: string, required: true }
                      label: { type: string, derived: "has(self.name) ? self.name : 'none'" }
                entityTypes:
                  Account:
                    traits: [Labelled]
                relationshipTypes:
                  Owns:
                    source: Account
                    target: Account
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: ownedBy
                    attributes:
                      share: { type: decimal }
                      heading: { type: string, derived: "'x'" }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> account = loader.loadClass(PACKAGE + ".Account");
            Class<?> owns = loader.loadClass(PACKAGE + ".Owns");

            assertThat(methods(nested(account, "Create"))).doesNotContain("label").contains("name");
            assertThat(methods(nested(owns, "Relate"))).doesNotContain("heading").contains("share");
            assertThat(methods(nested(owns, "Patch"))).doesNotContain("heading", "clearHeading").contains("clearShare");
            assertThat(owns.getMethod("heading").getGenericReturnType().getTypeName())
                    .isEqualTo("java.util.Optional<java.lang.String>");
            assertThat(Arrays.stream(nested(account, "Snapshot").getRecordComponents()).map(RecordComponent::getName))
                    .contains("name", "label");
        }
    }
}
