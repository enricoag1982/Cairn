package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.query.AttributeRef;
import io.github.enricoag1982.cairn.query.NumericAttributeRef;
import io.github.enricoag1982.cairn.query.OrderedAttributeRef;
import io.github.enricoag1982.cairn.reference.types.Account;
import io.github.enricoag1982.cairn.reference.types.Named;
import io.github.enricoag1982.cairn.reference.types.Organization;
import io.github.enricoag1982.cairn.reference.types.Owns;
import io.github.enricoag1982.cairn.reference.types.Party;
import io.github.enricoag1982.cairn.reference.types.Person;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The constants that hold the typed references of attributes (design D6 of {@code add-queries-and-aggregation}): one for
 * each attribute that a type declares, in upper snake case, with the typed reference of the attribute's value type, and
 * the rules for names that would clash. The generated code compiles with every lint warning and Javadoc problem an error.
 */
class AttributeReferencesTest {

    @TempDir
    Path directory;

    /** The constants of a generated interface that hold typed references, by name, with what each references. */
    private static Map<String, String> references(Class<?> type) {
        Map<String, String> found = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (AttributeRef.class.isAssignableFrom(field.getType())) {
                assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                        && Modifier.isPublic(field.getModifiers())).as(field.getName()).isTrue();
                try {
                    AttributeRef<?> reference = (AttributeRef<?>) field.get(null);
                    found.put(field.getName(), reference.name() + ":" + reference.valueType());
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return found;
    }

    /** The lines of a generated file that declare typed references. */
    private static List<String> constantLines(String source) {
        return source.lines().map(String::strip).filter(line -> line.contains(" = AttributeRef.")).toList();
    }

    // ------------------------------------------------------------------ the reference domain

    @Test
    void everyAttributeThatATypeDeclaresHasAConstantOfTheTypedReferenceOfItsValueType() {
        Account.BALANCE.of("a").sum("total");
        Person.BIRTH_DATE.of("p").ge(java.time.LocalDate.of(1990, 1, 1));
        Organization.LEI.of("o").eq("5493001KJTIIGC8Y1R12");
        Owns.SHARE.of("r").gt(new java.math.BigDecimal("50"));
        NumericAttributeRef<java.math.BigDecimal> balance = Account.BALANCE;
        OrderedAttributeRef<String> number = Account.NUMBER;
        OrderedAttributeRef<java.time.LocalDate> birthDate = Person.BIRTH_DATE;

        assertThat(balance.name()).isEqualTo("balance");
        assertThat(balance.valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(number.name()).isEqualTo("number");
        assertThat(birthDate.valueType()).isEqualTo(ValueType.DATE);
        assertThat(references(Account.class)).containsOnly(
                Map.entry("BALANCE", "balance:DECIMAL"), Map.entry("CURRENCY", "currency:STRING"),
                Map.entry("NUMBER", "number:STRING"));
        assertThat(references(Person.class)).containsOnly(Map.entry("BIRTH_DATE", "birthDate:DATE"));
        assertThat(references(Organization.class)).containsOnly(Map.entry("LEI", "lei:STRING"));
        assertThat(references(Owns.class)).containsOnly(Map.entry("SHARE", "share:DECIMAL"));
    }

    @Test
    void aTraitDeclaresTheConstantsOfItsAttributesAndTheTypesThatIncludeItInheritThem() {
        assertThat(references(Named.class)).containsOnly(Map.entry("NAME", "name:STRING"));
        assertThat(references(Party.class)).as("Party declares no attribute of its own").isEmpty();
        assertThat(Party.NAME).isSameAs(Named.NAME);
        assertThat(Organization.NAME).isSameAs(Named.NAME);
        assertThat(Person.NAME.of("p").eq("Alice")).isNotNull();
    }

    @Test
    void theConstantsThatEveryTypeHasAlreadyAreKept() {
        assertThat(Account.TYPE).isEqualTo("Account");
        assertThat(Owns.TYPE).isEqualTo("Owns");
    }

    // ------------------------------------------------------------------ names

    @Test
    void namesAreInUpperSnakeCase() {
        assertThat(Constants.snake("birthDate")).isEqualTo("BIRTH_DATE");
        assertThat(Constants.snake("balance")).isEqualTo("BALANCE");
        assertThat(Constants.snake("lei")).isEqualTo("LEI");
        assertThat(Constants.snake("LEI")).isEqualTo("LEI");
        assertThat(Constants.snake("leiCode")).isEqualTo("LEI_CODE");
        assertThat(Constants.snake("ISINCode")).isEqualTo("ISIN_CODE");
        assertThat(Constants.snake("birth_date")).isEqualTo("BIRTH_DATE");
        assertThat(Constants.snake("address2Line")).isEqualTo("ADDRESS2_LINE");
        assertThat(Constants.snake("x2")).isEqualTo("X2");
        assertThat(Constants.snake("Name")).isEqualTo("NAME");
        assertThat(Constants.snake("NameOfTheLEI")).isEqualTo("NAME_OF_THE_LEI");
        assertThat(Constants.snake("a_B")).isEqualTo("A_B");
        assertThat(Constants.snake("straße")).as("the German sharp s").isEqualTo("STRASSE");
    }

    @Test
    void everyValueTypeGetsItsTypedReferenceAndTheGeneratedCodeCompilesWithoutWarnings() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: values, version: "1.0.0" }
                entityTypes:
                  Sample:
                    description: "A sample with every value type."
                    attributes:
                      text: { type: string, required: true, description: "Some text, with <markup> & an @tag." }
                      flag: { type: boolean }
                      count: { type: int }
                      big: { type: long, required: true }
                      price: { type: decimal }
                      day: { type: date }
                      moment: { type: instant, required: true }
                      old: { type: string, deprecated: true }
                """);

        assertThat(text(generated, "Sample")).contains("OrderedAttributeRef<String> TEXT = AttributeRef.string(\"text\");")
                .contains("AttributeRef<Boolean> FLAG = AttributeRef.bool(\"flag\");")
                .contains("NumericAttributeRef<Integer> COUNT = AttributeRef.integer(\"count\");")
                .contains("NumericAttributeRef<Long> BIG = AttributeRef.longInteger(\"big\");")
                .contains("NumericAttributeRef<BigDecimal> PRICE = AttributeRef.decimal(\"price\");")
                .contains("OrderedAttributeRef<LocalDate> DAY = AttributeRef.date(\"day\");")
                .contains("OrderedAttributeRef<Instant> MOMENT = AttributeRef.instant(\"moment\");")
                .contains("@Deprecated\n    OrderedAttributeRef<String> OLD = AttributeRef.string(\"old\");")
                .contains("&lt;markup&gt; &amp; an &#64;tag.");
        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(references(loader.loadClass(PACKAGE + ".Sample"))).containsOnly(
                    Map.entry("TEXT", "text:STRING"), Map.entry("FLAG", "flag:BOOLEAN"), Map.entry("COUNT", "count:INT"),
                    Map.entry("BIG", "big:LONG"), Map.entry("PRICE", "price:DECIMAL"), Map.entry("DAY", "day:DATE"),
                    Map.entry("MOMENT", "moment:INSTANT"), Map.entry("OLD", "old:STRING"));
        }
    }

    @Test
    void aNameThatWouldClashGetsTheSuffixUntilItClashesWithNothing() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: clashes, version: "1.0.0" }
                traits:
                  Dated:
                    attributes:
                      birthDate: { type: date }
                entityTypes:
                  NUMBER: {}
                  Thing:
                    traits: [Dated]
                    attributes:
                      type: { type: string }
                      number: { type: int }
                      birth_date: { type: date }
                      Type_: { type: string }
                      leiCode: { type: string }
                      lei_code: { type: string }
                  Sub:
                    supertype: Thing
                    attributes:
                      sub_type: { type: string }
                      subType: { type: string }
                relationshipTypes:
                  Link:
                    source: Thing
                    target: Sub
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: linkedFrom
                    attributes:
                      type: { type: string }
                      weight: { type: int }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(references(loader.loadClass(PACKAGE + ".Dated"))).containsOnly(
                    Map.entry("BIRTH_DATE", "birthDate:DATE"));
            assertThat(references(loader.loadClass(PACKAGE + ".Thing"))).containsOnly(
                    Map.entry("TYPE_", "type:STRING"), Map.entry("NUMBER_", "number:INT"),
                    Map.entry("BIRTH_DATE_", "birth_date:DATE"), Map.entry("TYPE__", "Type_:STRING"),
                    Map.entry("LEI_CODE", "leiCode:STRING"), Map.entry("LEI_CODE_", "lei_code:STRING"));
            assertThat(references(loader.loadClass(PACKAGE + ".Sub"))).containsOnly(
                    Map.entry("SUB_TYPE", "sub_type:STRING"), Map.entry("SUB_TYPE_", "subType:STRING"));
            assertThat(references(loader.loadClass(PACKAGE + ".Link"))).containsOnly(
                    Map.entry("TYPE_", "type:STRING"), Map.entry("WEIGHT", "weight:INT"));
            assertThat(loader.loadClass(PACKAGE + ".Thing").getField("TYPE").get(null)).isEqualTo("Thing");
            assertThat(loader.loadClass(PACKAGE + ".Link").getField("TYPE").get(null)).isEqualTo("Link");
        }
    }

    @Test
    void theConstantsOfATypeDoNotChangeWhenAnotherTypeIsAdded() throws Exception {
        String model = """
                cairn: 1
                origin: { name: stable, version: "1.0.0" }
                entityTypes:
                  Thing:
                    attributes:
                      birthDate: { type: date }
                      birth_date: { type: date }
                """;
        String bigger = model + "  Other:\n    attributes:\n      birthDate: { type: int }\n      birth_date: { type: int }\n";
        Path first = GeneratedSource.document(directory, "first.cairn.yaml", model);
        Path secondDirectory = java.nio.file.Files.createDirectory(directory.resolve("second"));
        Path second = GeneratedSource.document(secondDirectory, "second.cairn.yaml", bigger);

        Generation.Generated one = generate(first);
        Generation.Generated two = generate(second);

        assertThat(constantLines(text(two, "Thing"))).isNotEmpty().isEqualTo(constantLines(text(one, "Thing")));
        assertThat(constantLines(text(two, "Other"))).containsExactly(
                "NumericAttributeRef<Integer> BIRTH_DATE = AttributeRef.integer(\"birthDate\");",
                "NumericAttributeRef<Integer> BIRTH_DATE_ = AttributeRef.integer(\"birth_date\");");
    }

    @Test
    void aTypeNamedLikeAConstantKeepsTheConstantFromHidingIt() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: hiding, version: "1.0.0" }
                entityTypes:
                  BALANCE:
                    attributes:
                      amount: { type: decimal }
                  Account:
                    attributes:
                      balance: { type: decimal }
                  Child:
                    supertype: Account
                relationshipTypes:
                  AMOUNT:
                    source: Account
                    target: BALANCE
                    targetsPerSource: "0..*"
                    sourcesPerTarget: "0..*"
                    inverseName: amountOf
                    attributes:
                      amount: { type: decimal }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(references(loader.loadClass(PACKAGE + ".Account"))).containsOnly(
                    Map.entry("BALANCE_", "balance:DECIMAL"));
            assertThat(references(loader.loadClass(PACKAGE + ".BALANCE"))).containsOnly(
                    Map.entry("AMOUNT_", "amount:DECIMAL"));
            assertThat(references(loader.loadClass(PACKAGE + ".AMOUNT"))).containsOnly(
                    Map.entry("AMOUNT_", "amount:DECIMAL"));
        }
    }

    @Test
    void typesNamedLikeTheTypedReferencesStillCompile() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: library, version: "1.0.0" }
                entityTypes:
                  AttributeRef: { attributes: { name: { type: string }, count: { type: long } } }
                  OrderedAttributeRef: { attributes: { day: { type: date } } }
                  NumericAttributeRef: { attributes: { at: { type: instant }, flag: { type: boolean } } }
                  BigDecimal: { attributes: { price: { type: decimal } } }
                  Boolean: { attributes: { switch: { type: boolean } } }
                """);

        try (URLClassLoader loader = compile(generated, directory).loader()) {
            assertThat(references(loader.loadClass(PACKAGE + ".AttributeRef"))).containsOnly(
                    Map.entry("NAME", "name:STRING"), Map.entry("COUNT", "count:LONG"));
            assertThat(Arrays.stream(loader.loadClass(PACKAGE + ".BigDecimal").getDeclaredFields())
                    .map(Field::getName).toList()).contains("PRICE");
        }
    }

    @Test
    void theConstantsAreGeneratedTheSameWayEveryTime() throws Exception {
        String model = """
                cairn: 1
                origin: { name: same, version: "1.0.0" }
                entityTypes:
                  Thing: { attributes: { birthDate: { type: date }, birth_date: { type: date } } }
                """;
        Generation.Generated first = generate(directory, model);
        Path elsewhere = java.nio.file.Files.createDirectory(directory.resolve("again"));
        Generation.Generated second = generate(GeneratedSource.document(elsewhere, "model.cairn.yaml", model));

        assertThat(GeneratedSource.byPath(second)).isEqualTo(GeneratedSource.byPath(first));
        assertThat(List.copyOf(references(compile(first, directory).loader().loadClass(PACKAGE + ".Thing")).keySet()))
                .containsExactly("BIRTH_DATE", "BIRTH_DATE_");
    }
}
