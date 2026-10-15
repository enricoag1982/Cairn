package io.github.enricoag1982.cairn.codegen;

import static io.github.enricoag1982.cairn.codegen.GeneratedSource.PACKAGE;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.compile;
import static io.github.enricoag1982.cairn.codegen.GeneratedSource.generate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.data.ConflictCheck;
import io.github.enricoag1982.cairn.data.EffectivePeriod;
import io.github.enricoag1982.cairn.data.EntityId;
import io.github.enricoag1982.cairn.data.EntityState;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.Basis;
import io.github.enricoag1982.cairn.schema.Value;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every value type, required and optional, through the generated builders and snapshots, which the test loads and calls
 * by reflection after compiling them.
 */
class ValueTypesTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final EntityId ID = new EntityId("00000000-0000-7000-8000-000000000001");

    @TempDir
    Path directory;

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : (target instanceof Class<?> type ? type : target.getClass()).getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    return method.invoke(target instanceof Class<?> ? null : target, arguments);
                } catch (IllegalArgumentException e) {
                    continue;
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?> nested(Class<?> type, String name) {
        for (Class<?> member : type.getClasses()) {
            if (member.getSimpleName().equals(name)) {
                return member;
            }
        }
        throw new AssertionError("No " + name + " in " + type);
    }

    private Class<?> kitchen(URLClassLoader loader) throws ClassNotFoundException {
        return loader.loadClass(PACKAGE + ".Kitchen");
    }

    private URLClassLoader compileKitchen() throws Exception {
        Generation.Generated generated = generate(directory, """
                cairn: 1
                origin: { name: kitchen, version: "1.0.0" }
                entityTypes:
                  Kitchen:
                    attributes:
                      label: { type: string, required: true }
                      open: { type: boolean, required: true }
                      seats: { type: int, required: true }
                      meals: { type: long, required: true }
                      area: { type: decimal, required: true }
                      built: { type: date, required: true }
                      opened: { type: instant, required: true }
                      nickname: { type: string }
                      lit: { type: boolean }
                      chairs: { type: int }
                      served: { type: long }
                      volume: { type: decimal }
                      renovated: { type: date }
                      cleaned: { type: instant }
                """);
        return compile(generated, directory).loader();
    }

    @Test
    void theCreateBuilderSetsEveryValueTypeAndProducesTheWriteOfTheGenericApi() throws Exception {
        try (URLClassLoader loader = compileKitchen()) {
            Class<?> kitchen = kitchen(loader);
            Object builder = call(kitchen, "create");
            call(builder, "as", "k1");
            call(builder, "id", ID);
            call(builder, "label", "Main");
            call(builder, "open", true);
            call(builder, "seats", 12);
            call(builder, "meals", 5_000_000_000L);
            call(builder, "area", new BigDecimal("18.5"));
            call(builder, "built", LocalDate.of(1999, 12, 31));
            call(builder, "opened", FROM);
            call(builder, "nickname", "Galley");
            call(builder, "lit", false);
            call(builder, "chairs", 4);
            call(builder, "served", 7L);
            call(builder, "volume", new BigDecimal("30"));
            call(builder, "renovated", LocalDate.of(2020, 2, 29));
            call(builder, "cleaned", FROM.plusSeconds(60));

            Object write = call(builder, "effectiveFrom", FROM);

            Map<String, Value> expected = new LinkedHashMap<>();
            expected.put("label", Value.of("Main"));
            expected.put("open", Value.of(true));
            expected.put("seats", Value.of(12));
            expected.put("meals", Value.of(5_000_000_000L));
            expected.put("area", Value.of(new BigDecimal("18.5")));
            expected.put("built", Value.of(LocalDate.of(1999, 12, 31)));
            expected.put("opened", Value.of(FROM));
            expected.put("nickname", Value.of("Galley"));
            expected.put("lit", Value.of(false));
            expected.put("chairs", Value.of(4));
            expected.put("served", Value.of(7L));
            expected.put("volume", Value.of(new BigDecimal("30")));
            expected.put("renovated", Value.of(LocalDate.of(2020, 2, 29)));
            expected.put("cleaned", Value.of(FROM.plusSeconds(60)));
            assertThat(write).isEqualTo(new Write.Create(Optional.of(new TemporaryId("k1")), Optional.of(ID), "Kitchen",
                    EffectivePeriod.from(FROM), expected));
            assertThat(((Write.Create) write).values().keySet()).containsExactlyElementsOf(expected.keySet());
        }
    }

    @Test
    void thePatchBuilderSetsAndClearsAndTheLastCallWins() throws Exception {
        try (URLClassLoader loader = compileKitchen()) {
            Class<?> kitchen = kitchen(loader);
            EntityId target = ID;
            Object builder = call(kitchen, "patch", target);
            call(builder, "label", "Main");
            call(builder, "clearNickname");
            call(builder, "clearChairs");
            call(builder, "chairs", 6);
            call(builder, "nickname", "Galley");
            call(builder, "clearNickname");
            call(builder, "clearLit");
            call(builder, "lit", true);
            ConflictCheck check = ConflictCheck.perEntity(new Basis(3));
            call(builder, "check", check);

            Object write = call(builder, "effective", EffectivePeriod.between(FROM, FROM.plusSeconds(1)));

            assertThat(write).isEqualTo(new Write.Patch(target, EffectivePeriod.between(FROM, FROM.plusSeconds(1)),
                    Map.of("label", Value.of("Main"), "chairs", Value.of(6), "lit", Value.of(true)),
                    Set.of("nickname"), check));
        }
    }

    @Test
    void theReplaceBuilderSetsTheCompleteValues() throws Exception {
        try (URLClassLoader loader = compileKitchen()) {
            Object builder = call(kitchen(loader), "replace", ID);
            call(builder, "label", "Main");

            Object write = call(builder, "effectiveFrom", FROM);

            assertThat(write).isEqualTo(new Write.Replace(ID, EffectivePeriod.from(FROM),
                    Map.of("label", Value.of("Main")), ConflictCheck.none()));
        }
    }

    @Test
    void theSnapshotReadsEveryValueTypeFromAState() throws Exception {
        try (URLClassLoader loader = compileKitchen()) {
            Class<?> kitchen = kitchen(loader);
            Map<String, Value> values = new LinkedHashMap<>();
            values.put("label", Value.of("Main"));
            values.put("open", Value.of(true));
            values.put("seats", Value.of(12));
            values.put("meals", Value.of(5_000_000_000L));
            values.put("area", Value.of(new BigDecimal("18.5")));
            values.put("built", Value.of(LocalDate.of(1999, 12, 31)));
            values.put("opened", Value.of(FROM));
            values.put("chairs", Value.of(4));
            values.put("volume", Value.of(new BigDecimal("30")));
            EntityState state = new EntityState(ID, "Kitchen", values, EffectivePeriod.from(FROM), FROM, new Basis(1));

            Object read = call(kitchen, "from", state);

            assertThat(call(read, "id")).isEqualTo(ID);
            assertThat(call(read, "entityState")).isEqualTo(state);
            assertThat(call(read, "label")).isEqualTo("Main");
            assertThat(call(read, "open")).isEqualTo(true);
            assertThat(call(read, "seats")).isEqualTo(12);
            assertThat(call(read, "meals")).isEqualTo(5_000_000_000L);
            assertThat(call(read, "area")).isEqualTo(new BigDecimal("18.5"));
            assertThat(call(read, "built")).isEqualTo(LocalDate.of(1999, 12, 31));
            assertThat(call(read, "opened")).isEqualTo(FROM);
            assertThat(call(read, "nickname")).isEqualTo(Optional.empty());
            assertThat(call(read, "lit")).isEqualTo(Optional.empty());
            assertThat(call(read, "chairs")).isEqualTo(Optional.of(4));
            assertThat(call(read, "served")).isEqualTo(Optional.empty());
            assertThat(call(read, "volume")).isEqualTo(Optional.of(new BigDecimal("30")));
            assertThat(call(read, "renovated")).isEqualTo(Optional.empty());
            assertThat(call(read, "cleaned")).isEqualTo(Optional.empty());
            assertThat(read.getClass().getSimpleName()).isEqualTo("Snapshot");
            assertThat(read.getClass()).isEqualTo(nested(kitchen, "Snapshot"));
            assertThat(read.getClass().isRecord()).isTrue();
        }
    }

    @Test
    void aStateThatLacksARequiredValueOrHasAValueOfAnotherTypeIsAProgrammingError() throws Exception {
        try (URLClassLoader loader = compileKitchen()) {
            Class<?> kitchen = kitchen(loader);
            Map<String, Value> values = new LinkedHashMap<>();
            values.put("label", Value.of("Main"));
            EntityState lacking = new EntityState(ID, "Kitchen", values, EffectivePeriod.from(FROM), FROM, new Basis(1));
            values.put("open", Value.of("yes"));
            EntityState wrong = new EntityState(ID, "Kitchen", values, EffectivePeriod.from(FROM), FROM, new Basis(1));

            assertThatThrownBy(() -> call(kitchen, "from", lacking)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("open");
            assertThatThrownBy(() -> call(kitchen, "from", wrong)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("open").hasMessageContaining("BOOLEAN");
        }
    }
}
