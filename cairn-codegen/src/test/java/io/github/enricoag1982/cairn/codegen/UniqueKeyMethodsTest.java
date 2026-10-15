package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.data.UniqueKey;
import io.github.enricoag1982.cairn.schema.Value;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code by<Attribute>} methods that make unique keys (ADR-0026, design D7 of {@code add-unique-key-references}): one
 * static method for each unique attribute of an entity type, inherited ones and those of included traits too, with the
 * Java type of the attribute, none for relationship types, and names that go through the clash rules. The generated
 * source is compiled with every lint warning and Javadoc problem an error.
 */
class UniqueKeyMethodsTest {

    @TempDir
    Path directory;

    private static List<String> keyMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isStatic(method.getModifiers()))
                .map(Method::getName).filter(name -> name.startsWith("by")).sorted().toList();
    }

    private static Object key(Class<?> type, String method, Class<?> parameter, Object value) throws Exception {
        return type.getMethod(method, parameter).invoke(null, value);
    }

    @Test
    void everyUniqueAttributeGetsAStaticMethodThatTakesItsJavaTypeAndReturnsTheKey() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: keys, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      text: { type: string, unique: true, required: true }
                      flag: { type: boolean, unique: true }
                      count: { type: int, unique: true }
                      big: { type: long, unique: true }
                      amount: { type: decimal, unique: true }
                      day: { type: date, unique: true }
                      moment: { type: instant, unique: true }
                      note: { type: string }
                """);

        assertThat(text(generated, "Thing")).contains("static UniqueKey byText(String value) {")
                .contains("return new UniqueKey(TYPE, \"text\", Value.of(value));")
                .contains("static UniqueKey byFlag(boolean value) {").contains("static UniqueKey byCount(int value) {")
                .contains("static UniqueKey byBig(long value) {").contains("static UniqueKey byAmount(BigDecimal value) {")
                .contains("static UniqueKey byDay(LocalDate value) {").contains("static UniqueKey byMoment(Instant value) {")
                .doesNotContain("byNote");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> thing = loader.loadClass(PACKAGE + ".Thing");

            assertThat(keyMethods(thing)).containsExactly("byAmount", "byBig", "byCount", "byDay", "byFlag", "byMoment",
                    "byText");
            assertThat(thing.getMethod("byText", String.class).getReturnType()).isEqualTo(UniqueKey.class);
            assertThat(key(thing, "byText", String.class, "x")).isEqualTo(new UniqueKey("Thing", "text", Value.of("x")));
            assertThat(key(thing, "byFlag", boolean.class, true)).isEqualTo(new UniqueKey("Thing", "flag",
                    Value.of(true)));
            assertThat(key(thing, "byCount", int.class, 7)).isEqualTo(new UniqueKey("Thing", "count", Value.of(7)));
            assertThat(key(thing, "byBig", long.class, 7L)).isEqualTo(new UniqueKey("Thing", "big", Value.of(7L)));
            assertThat(key(thing, "byAmount", BigDecimal.class, new BigDecimal("1.50"))).isEqualTo(
                    new UniqueKey("Thing", "amount", Value.of(new BigDecimal("1.50"))));
            assertThat(key(thing, "byDay", LocalDate.class, LocalDate.of(2026, 5, 17))).isEqualTo(
                    new UniqueKey("Thing", "day", Value.of(LocalDate.of(2026, 5, 17))));
            Instant moment = Instant.parse("2026-05-17T10:00:00Z");
            assertThat(key(thing, "byMoment", Instant.class, moment)).isEqualTo(
                    new UniqueKey("Thing", "moment", Value.of(moment)));
        }
    }

    @Test
    void inheritedAttributesAndThoseOfTraitsGetAMethodOnEveryTypeThatHasThemAndTheKeyNamesThatType() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: keys, version: "1.0.0" }
                traits:
                  Tagged:
                    attributes:
                      tag: { type: string, unique: true }
                entityTypes:
                  Asset:
                    abstract: true
                    traits: [Tagged]
                    attributes:
                      serial: { type: long, unique: true }
                  Car:
                    supertype: Asset
                    attributes:
                      plate: { type: string, unique: true }
                  House:
                    supertype: Asset
                relationshipTypes:
                  Near:
                    source: Asset
                    target: Asset
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: nearTo
                    attributes:
                      code: { type: string, unique: true }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> asset = loader.loadClass(PACKAGE + ".Asset");
            Class<?> car = loader.loadClass(PACKAGE + ".Car");
            Class<?> house = loader.loadClass(PACKAGE + ".House");

            assertThat(keyMethods(asset)).containsExactly("bySerial", "byTag");
            assertThat(keyMethods(car)).containsExactly("byPlate", "bySerial", "byTag");
            assertThat(keyMethods(house)).containsExactly("bySerial", "byTag");
            assertThat(keyMethods(loader.loadClass(PACKAGE + ".Tagged"))).as("a trait is no entity type").isEmpty();
            assertThat(keyMethods(loader.loadClass(PACKAGE + ".Near"))).as("keys name entities only").isEmpty();
            assertThat(key(car, "byTag", String.class, "T-1")).isEqualTo(new UniqueKey("Car", "tag", Value.of("T-1")));
            assertThat(key(asset, "byTag", String.class, "T-1")).isEqualTo(new UniqueKey("Asset", "tag",
                    Value.of("T-1")));
            assertThat(key(house, "bySerial", long.class, 9L)).isEqualTo(new UniqueKey("House", "serial", Value.of(9L)));
        }
    }

    @Test
    void aMethodThatWouldClashWithAnAccessorOrAnotherKeyGetsTheSuffixLikeOtherGeneratedNames() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: clashes, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      x: { type: string, unique: true }
                      byX: { type: string }
                      y: { type: string, unique: true }
                      byY_: { type: string }
                """);

        assertThat(text(generated, "Thing")).contains("static UniqueKey byX_(String value) {")
                .contains("static UniqueKey byY(String value) {").contains("Optional<String> byX();");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> thing = loader.loadClass(PACKAGE + ".Thing");

            assertThat(keyMethods(thing)).as("byY does not clash with the accessor byY_").containsExactly("byX_", "byY");
            assertThat(key(thing, "byX_", String.class, "a")).isEqualTo(new UniqueKey("Thing", "x", Value.of("a")));
        }
    }

    @Test
    void namesThatAreKeywordsOrLibraryTypesStillCompileWithTheKeyMethods() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: awkward, version: "1.0.0" }
                entityTypes:
                  UniqueKey:
                    attributes:
                      value: { type: string, unique: true }
                      class: { type: string, unique: true }
                      id: { type: string, unique: true }
                      from: { type: string, unique: true }
                  Value:
                    attributes:
                      Value: { type: int, unique: true }
                      TYPE: { type: string, unique: true }
                  String:
                    attributes:
                      length: { type: long, unique: true }
                  Optional:
                    attributes:
                      present: { type: string, unique: true }
                  record:
                    attributes:
                      var: { type: string, unique: true }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            Class<?> uniqueKey = loader.loadClass(PACKAGE + ".UniqueKey");
            Class<?> value = loader.loadClass(PACKAGE + ".Value");

            assertThat(keyMethods(uniqueKey)).containsExactly("byClass", "byFrom", "byId", "byValue");
            assertThat(uniqueKey.getMethod("byValue", String.class).getReturnType()).isEqualTo(UniqueKey.class);
            assertThat(key(uniqueKey, "byValue", String.class, "v")).isEqualTo(new UniqueKey("UniqueKey", "value",
                    Value.of("v")));
            assertThat(key(value, "byValue", int.class, 3)).isEqualTo(new UniqueKey("Value", "Value", Value.of(3)));
            assertThat(key(loader.loadClass(PACKAGE + ".String"), "byLength", long.class, 3L)).isEqualTo(
                    new UniqueKey("String", "length", Value.of(3L)));
            assertThat(key(loader.loadClass(PACKAGE + ".record_"), "byVar", String.class, "a")).isEqualTo(
                    new UniqueKey("record", "var", Value.of("a")));
        }
    }

    @Test
    void aDeprecatedUniqueAttributeGetsADeprecatedMethodThatCompilesWithoutWarnings() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: old, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      legacy: { type: string, unique: true, deprecated: true }
                """);

        assertThat(text(generated, "Thing")).contains("@deprecated The attribute").contains("@Deprecated\n")
                .contains("static UniqueKey byLegacy(String value) {");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(loader.loadClass(PACKAGE + ".Thing").getMethod("byLegacy", String.class)
                    .isAnnotationPresent(Deprecated.class)).isTrue();
        }
    }

    @Test
    void anEntityTypeWithoutUniqueAttributesHasNoKeyMethodsAndNoImportOfTheKey() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: plain, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      text: { type: string }
                """);

        assertThat(text(generated, "Thing")).doesNotContain("UniqueKey");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(keyMethods(loader.loadClass(PACKAGE + ".Thing"))).isEmpty();
        }
    }
}
