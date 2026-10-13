package io.github.enricoag1982.cairn.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Bound;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.Literal;
import io.github.enricoag1982.cairn.schema.Multiplicity;
import io.github.enricoag1982.cairn.schema.Range;
import io.github.enricoag1982.cairn.schema.RelationshipKind;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.TraitDefinition;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link ReferenceModel} matches every type, attribute and relationship type in
 * {@code docs/reference/reference-domain.md}.
 */
class ReferenceModelTest {

    private static final Path PAGE = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-domain.md");
    private static final Pattern ATTRIBUTE = Pattern.compile("`(\\w+): (\\w+)`(.*)");
    private static final Pattern RANGE = Pattern.compile(
            "(-?[\\d.]+) \\((inclusive|exclusive)\\) to (-?[\\d.]+) \\((inclusive|exclusive)\\)");

    private static List<List<String>> typeRows;
    private static List<List<String>> relationshipRows;
    private static final SchemaDefinition MODEL = ReferenceModel.definition();

    @BeforeAll
    static void readPage() throws IOException {
        List<List<String>> rows = Files.readAllLines(PAGE).stream()
                .filter(line -> line.startsWith("| `"))
                .map(ReferenceModelTest::cells)
                .toList();
        typeRows = rows.stream().filter(row -> row.size() == 3).toList();
        relationshipRows = rows.stream().filter(row -> row.size() == 8).toList();
    }

    @Test
    void pageAndFixtureHaveTheSameTypes() {
        List<String> pageTypes = typeRows.stream().map(row -> unquote(row.get(0))).toList();
        List<String> fixtureTypes = new ArrayList<>();
        MODEL.traits().forEach(trait -> fixtureTypes.add(trait.name()));
        MODEL.entityTypes().forEach(type -> fixtureTypes.add(type.name()));
        assertThat(fixtureTypes).containsExactlyInAnyOrderElementsOf(pageTypes);

        List<String> pageRelationships = relationshipRows.stream().map(row -> unquote(row.get(0))).toList();
        assertThat(MODEL.relationshipTypes()).map(RelationshipTypeDefinition::name)
                .containsExactlyInAnyOrderElementsOf(pageRelationships);
    }

    @Test
    void entityTypesAndTraitsMatchThePage() {
        Map<String, EntityTypeDefinition> entityTypes = byName(MODEL.entityTypes(), EntityTypeDefinition::name);
        Map<String, TraitDefinition> traits = byName(MODEL.traits(), TraitDefinition::name);
        for (List<String> row : typeRows) {
            String name = unquote(row.get(0));
            String kind = row.get(1);
            List<AttributeDefinition> expected = attributes(row.get(2));
            if (kind.startsWith("trait")) {
                assertThat(traits.get(name).attributes()).as(name).isEqualTo(expected);
                continue;
            }
            EntityTypeDefinition type = entityTypes.get(name);
            assertThat(type.isAbstract()).as(name + " abstract").isEqualTo(kind.startsWith("abstract"));
            assertThat(type.supertype()).as(name + " supertype").isEqualTo(referenceAfter(kind, "extends"));
            assertThat(type.traits()).as(name + " traits")
                    .isEqualTo(referenceAfter(kind, "includes").map(List::of).orElse(List.of()));
            assertThat(type.attributes()).as(name + " attributes").isEqualTo(expected);
        }
    }

    @Test
    void relationshipTypesMatchThePage() {
        Map<String, RelationshipTypeDefinition> types = byName(MODEL.relationshipTypes(),
                RelationshipTypeDefinition::name);
        for (List<String> row : relationshipRows) {
            String name = unquote(row.get(0));
            RelationshipTypeDefinition type = types.get(name);
            assertThat(type.source()).as(name + " source").isEqualTo(unquote(row.get(1)));
            assertThat(type.target()).as(name + " target").isEqualTo(unquote(row.get(2)));
            assertThat(type.targetsPerSource()).as(name + " targets per source").isEqualTo(multiplicity(row.get(3)));
            assertThat(type.sourcesPerTarget()).as(name + " sources per target").isEqualTo(multiplicity(row.get(4)));
            assertThat(type.kind()).as(name + " kind")
                    .isEqualTo(RelationshipKind.valueOf(row.get(5).toUpperCase(Locale.ROOT)));
            assertThat(type.attributes()).as(name + " attributes").isEqualTo(attributes(row.get(6)));
            assertThat(type.inverseName()).as(name + " inverse name").isEqualTo(unquote(row.get(7)));
        }
    }

    private static List<String> cells(String line) {
        String inner = line.substring(1, line.lastIndexOf('|'));
        return Arrays.stream(inner.split("\\|")).map(String::strip).toList();
    }

    private static String unquote(String cell) {
        return cell.replace("`", "").strip();
    }

    private static Optional<String> referenceAfter(String text, String keyword) {
        Matcher matcher = Pattern.compile(keyword + " `(\\w+)`").matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Multiplicity multiplicity(String cell) {
        String[] bounds = cell.split("\\.\\.");
        int maximum = bounds[1].equals("*") ? Multiplicity.UNBOUNDED : Integer.parseInt(bounds[1]);
        return new Multiplicity(Integer.parseInt(bounds[0]), maximum);
    }

    private static List<AttributeDefinition> attributes(String cell) {
        if (!cell.contains("`")) {
            return List.of();
        }
        List<AttributeDefinition> result = new ArrayList<>();
        for (String part : cell.split(";")) {
            Matcher matcher = ATTRIBUTE.matcher(part.strip());
            assertThat(matcher.matches()).as("attribute cell part '%s'", part).isTrue();
            String flags = matcher.group(3).replaceAll("\\([A-Z][^)]*\\)", "");
            List<String> words = Arrays.stream(flags.split(",")).map(String::strip).toList();
            Matcher range = RANGE.matcher(flags);
            result.add(new AttributeDefinition(
                    matcher.group(1),
                    ValueType.valueOf(matcher.group(2).toUpperCase(Locale.ROOT)),
                    words.contains("required"),
                    words.contains("unique"),
                    range.find() ? Optional.of(range(range)) : Optional.empty(),
                    List.of(),
                    false));
        }
        return result;
    }

    private static Range range(Matcher matcher) {
        return Range.between(
                bound(matcher.group(1), matcher.group(2)),
                bound(matcher.group(3), matcher.group(4)));
    }

    private static Bound bound(String number, String inclusivity) {
        Literal value = number.contains(".") ? Literal.of(new BigDecimal(number)) : Literal.of(Integer.parseInt(number));
        return new Bound(value, inclusivity.equals("inclusive"));
    }

    private static <T> Map<String, T> byName(List<T> items, Function<T, String> nameOf) {
        return items.stream().collect(Collectors.toMap(nameOf, Function.identity()));
    }
}
