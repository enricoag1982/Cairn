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
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
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
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link ReferenceModel} matches every type, attribute, string constraint, relationship type and rule in
 * {@code docs/reference/reference-domain.md}.
 */
class ReferenceModelTest {

    private static final Path PAGE = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/reference-domain.md");
    private static final Pattern ATTRIBUTE = Pattern.compile("`(\\w+): (\\w+)`(.*)");
    private static final Pattern RANGE = Pattern.compile(
            "(-?[\\d.]+) \\((inclusive|exclusive)\\) to (-?[\\d.]+) \\((inclusive|exclusive)\\)");
    private static final Pattern PATTERN = Pattern.compile("pattern `([^`]+)`");
    private static final Pattern ALLOWED_VALUES = Pattern.compile("one of ((?:`[^`]+`(?:, )?)+)");
    private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");

    private static List<List<String>> typeRows;
    private static List<List<String>> relationshipRows;
    private static List<List<String>> ruleRows;
    private static final SchemaDefinition MODEL = ReferenceModel.definition();

    @BeforeAll
    static void readPage() throws IOException {
        List<List<String>> rows = Files.readAllLines(PAGE).stream()
                .filter(line -> line.startsWith("| `"))
                .map(ReferenceModelTest::cells)
                .toList();
        typeRows = rows.stream().filter(row -> row.size() == 3).toList();
        relationshipRows = rows.stream().filter(row -> row.size() == 8).toList();
        ruleRows = rows.stream().filter(row -> row.size() == 5).toList();
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

    @Test
    void stringConstraintsMatchThePage() {
        Map<String, List<Object>> onPage = new TreeMap<>();
        for (List<String> row : typeRows) {
            attributes(row.get(2)).forEach(attribute -> collectConstraints(onPage, unquote(row.get(0)), attribute));
        }
        Map<String, List<Object>> inModel = new TreeMap<>();
        for (EntityTypeDefinition type : MODEL.entityTypes()) {
            type.attributes().forEach(attribute -> collectConstraints(inModel, type.name(), attribute));
        }

        assertThat(onPage).containsOnlyKeys("Account.currency", "Organization.lei", "CustomerRole.segment");
        assertThat(inModel).isEqualTo(onPage);
    }

    @Test
    void rulesMatchThePage() {
        List<RuleDescriptor> rules = ReferenceModel.rules();
        assertThat(rules).map(RuleDescriptor::name)
                .containsExactlyInAnyOrderElementsOf(ruleRows.stream().map(row -> unquote(row.get(0))).toList());
        Map<String, RuleDescriptor> byName = byName(rules, RuleDescriptor::name);
        for (List<String> row : ruleRows) {
            String name = unquote(row.get(0));
            RuleDescriptor rule = byName.get(name);
            assertThat(rule.version()).as(name + " version").isEqualTo(Integer.parseInt(row.get(1)));
            assertThat(rule.appliesTo()).as(name + " type").isEqualTo(unquote(row.get(2)));
            assertThat(rule.description()).as(name + " description").isEqualTo(row.get(3));
            assertThat(rule.codes()).as(name + " codes").map(RuleCode::code)
                    .containsExactlyElementsOf(quoted(row.get(4)));
            assertThat(rule.codes()).as(name + " explanations").allSatisfy(code ->
                    assertThat(code.explanation()).as(code.code()).isNotBlank());
        }
    }

    @Test
    void ruleReferencesMatchThePage() {
        Map<String, List<RuleReference>> named = new TreeMap<>();
        MODEL.entityTypes().stream().filter(type -> !type.rules().isEmpty())
                .forEach(type -> named.put(type.name(), type.rules()));
        MODEL.relationshipTypes().stream().filter(type -> !type.rules().isEmpty())
                .forEach(type -> named.put(type.name(), type.rules()));

        Map<String, List<RuleReference>> expected = new TreeMap<>();
        for (List<String> row : ruleRows) {
            expected.computeIfAbsent(unquote(row.get(2)), type -> new ArrayList<>())
                    .add(new RuleReference(unquote(row.get(0)), Integer.parseInt(row.get(1))));
        }
        assertThat(named).isEqualTo(expected);
    }

    private static List<String> cells(String line) {
        String inner = line.substring(1, line.lastIndexOf('|'));
        return Arrays.stream(inner.split("\\|")).map(String::strip).toList();
    }

    private static String unquote(String cell) {
        return cell.replace("`", "").strip();
    }

    private static void collectConstraints(Map<String, List<Object>> into, String type, AttributeDefinition attribute) {
        if (attribute.pattern().isPresent() || !attribute.allowedValues().isEmpty()) {
            into.put(type + "." + attribute.name(), List.of(attribute.pattern(), attribute.allowedValues()));
        }
    }

    private static List<String> quoted(String cell) {
        Matcher matcher = QUOTED.matcher(cell);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
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
            String flags = matcher.group(3);
            Matcher pattern = PATTERN.matcher(flags);
            Optional<String> patternText = pattern.find() ? Optional.of(pattern.group(1)) : Optional.empty();
            Matcher allowed = ALLOWED_VALUES.matcher(flags);
            List<String> allowedValues = allowed.find() ? quoted(allowed.group(1)) : List.of();
            flags = flags.replaceAll(PATTERN.pattern(), "").replaceAll(ALLOWED_VALUES.pattern(), "")
                    .replaceAll("\\([A-Z][^)]*\\)", "");
            List<String> words = Arrays.stream(flags.split(",")).map(String::strip).toList();
            Matcher range = RANGE.matcher(flags);
            ValueType valueType = ValueType.valueOf(matcher.group(2).toUpperCase(Locale.ROOT));
            result.add(new AttributeDefinition(
                    matcher.group(1),
                    valueType,
                    words.contains("required"),
                    words.contains("unique"),
                    range.find() ? Optional.of(range(range, valueType)) : Optional.empty(),
                    List.of(),
                    false,
                    Optional.empty(),
                    List.of(),
                    allowedValues,
                    patternText,
                    OptionalInt.empty(),
                    OptionalInt.empty()));
        }
        return result;
    }

    private static Range range(Matcher matcher, ValueType valueType) {
        return Range.between(
                bound(matcher.group(1), matcher.group(2), valueType),
                bound(matcher.group(3), matcher.group(4), valueType));
    }

    /** A bound has the value type of its attribute, so that a schema document gives the same bounds. */
    private static Bound bound(String number, String inclusivity, ValueType valueType) {
        Literal value = valueType == ValueType.DECIMAL ? Literal.of(new BigDecimal(number))
                : Literal.of(Integer.parseInt(number));
        return new Bound(value, inclusivity.equals("inclusive"));
    }

    private static <T> Map<String, T> byName(List<T> items, Function<T, String> nameOf) {
        return items.stream().collect(Collectors.toMap(nameOf, Function.identity()));
    }
}
