package io.github.enricoag1982.cairn.example.hr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.TemporaryId;
import io.github.enricoag1982.cairn.data.Write;
import io.github.enricoag1982.cairn.schema.AttributeDefinition;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.EntityTypeDefinition;
import io.github.enricoag1982.cairn.schema.RelationshipTypeDefinition;
import io.github.enricoag1982.cairn.schema.RuleCode;
import io.github.enricoag1982.cairn.schema.RuleDescriptor;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.SchemaDefinition;
import io.github.enricoag1982.cairn.schema.Submission;
import io.github.enricoag1982.cairn.schema.Value;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/reference/hr-domain.md} and the example in step, as {@code ReferenceModelTest} does for the
 * reference domain: the page's tables of entity types, relationship types, rules and checks against the schema document
 * {@code hr-model.cairn.yaml} and the descriptors of the rules, and its table of people against
 * {@link Brightwater#EMPLOYEES} and the structure payload of HR-1.
 *
 * <p>The comparison reads the page by the headers of its tables and the words of its cells, so that it does not
 * depend on column widths, the order of rows, or the Markdown around a name. It is strict on what the page says: the
 * names, the attributes with whether they are required and unique, their allowed values and patterns, the expression
 * of those that are derived ({@code derived from} and the expression in a code span, among the details of the attribute),
 * the kind, source, target, multiplicities and inverse name of each relationship type, the version, type, description
 * and codes of each rule, and the type, expression and message of each check.
 */
class HrDomainPageTest {

    private static final Path PAGE = Path.of("").toAbsolutePath().getParent()
            .resolve("docs/reference/hr-domain.md");
    private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");
    private static final Pattern PATTERN_OF_LETTERS = Pattern.compile("(two|three) capital letters");
    private static final Pattern PATTERN_OF_DIGITS = Pattern.compile("`([^`]+)` and (two|three|four) digits");
    private static final Pattern DERIVED = Pattern.compile("derived from `([^`]+)`");
    private static final Pattern POSITION = Pattern.compile("(P-\\d+), (.+) \\(([A-Z]+)\\)");
    private static final Pattern SALARY = Pattern.compile("(\\d[\\d,]*) ([A-Z]{3})");
    private static final Map<String, Integer> COUNTS = Map.of("two", 2, "three", 3, "four", 4);
    private static final Map<String, ValueType> TYPE_WORDS = Map.of("decimal", ValueType.DECIMAL, "int", ValueType.INT,
            "long", ValueType.LONG, "boolean", ValueType.BOOLEAN, "string", ValueType.STRING,
            "date", ValueType.DATE, "dates", ValueType.DATE, "instant", ValueType.INSTANT,
            "instants", ValueType.INSTANT);

    private static List<String> lines;
    private static SchemaDefinition model;

    @BeforeAll
    static void readPageAndDocument() throws IOException {
        lines = Files.readAllLines(PAGE);
        model = Brightwater.schemaDocument().definition();
    }

    // ------------------------------------------------------------------ reading the page

    /** A table of the page: its headers, and its rows as cells by header. */
    private record Table(List<String> headers, List<Map<String, String>> rows) {

        /** The cell of a row under the header, or under its plural if the table has that instead. */
        String cell(Map<String, String> row, String header) {
            String found = headers.contains(header) ? header : header + "s";
            assertThat(headers).as("the header %s of the table", header).contains(found);
            return row.get(found);
        }
    }

    /** The table whose first header is the given words. */
    private static Table table(String firstHeader) {
        for (int i = 0; i + 1 < lines.size(); i++) {
            List<String> headers = cells(lines.get(i)).stream().map(HrDomainPageTest::plain).toList();
            if (!headers.isEmpty() && headers.getFirst().equals(firstHeader)
                    && lines.get(i + 1).trim().matches("\\|[\\s|:-]+\\|?")) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (int j = i + 2; j < lines.size() && lines.get(j).trim().startsWith("|"); j++) {
                    List<String> cells = cells(lines.get(j));
                    assertThat(cells).as("a row of the table %s: %s", firstHeader, lines.get(j))
                            .hasSameSizeAs(headers);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size(); c++) {
                        row.put(headers.get(c), cells.get(c));
                    }
                    rows.add(row);
                }
                return new Table(headers, rows);
            }
        }
        throw new AssertionError("The page has no table whose first header is " + firstHeader);
    }

    private static List<String> cells(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) {
            return List.of();
        }
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        return Arrays.stream(inner.split("\\|", -1)).map(String::trim).toList();
    }

    /** Text without Markdown: links reduced to their text, no code ticks or bold, white space collapsed. */
    private static String plain(String markdown) {
        return markdown.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").replace("`", "").replace("**", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** The code spans in a text. */
    private static List<String> quoted(String text) {
        Matcher matcher = QUOTED.matcher(text);
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.group(1));
        }
        return spans;
    }

    /** The index of the first character that is outside code spans, or -1. */
    private static int indexOutsideCode(String text, char wanted) {
        boolean code = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                code = !code;
            } else if (!code && c == wanted) {
                return i;
            }
        }
        return -1;
    }

    /** Splits at a separator that is outside parentheses and code spans. */
    private static List<String> split(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean code = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                code = !code;
            } else if (!code && c == '(') {
                depth++;
            } else if (!code && c == ')') {
                depth--;
            } else if (!code && depth == 0 && c == separator) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    // ------------------------------------------------------------------ entity types

    /** What the page says about an attribute; a type or a pattern it does not mention is null. */
    private record PageAttribute(ValueType type, boolean required, boolean unique, Set<String> allowedValues,
            String pattern, String derived) {
    }

    /**
     * Reads the attributes cell: segments separated by commas, each with the code spans before its parenthesis as
     * the attribute names (&quot;{@code a} and {@code b}&quot; are two) and, in the parenthesis, flags separated by
     * commas: required, unique, a type word, a pattern in words, {@code derived from} and the expression in a code
     * span, or a colon and the allowed values.
     */
    private static Map<String, PageAttribute> attributes(String cell) {
        Map<String, PageAttribute> attributes = new TreeMap<>();
        for (String segment : split(cell, ',')) {
            int open = segment.indexOf('(');
            List<String> names = quoted(open < 0 ? segment : segment.substring(0, open));
            assertThat(names).as("the attribute names in '%s'", segment.trim()).isNotEmpty();
            String qualifier = open < 0 ? "" : segment.substring(open + 1, segment.lastIndexOf(')'));
            int colon = indexOutsideCode(qualifier, ':');
            String flags = colon < 0 ? qualifier : qualifier.substring(0, colon);
            Set<String> allowed = colon < 0 ? Set.of()
                    : new LinkedHashSet<>(quoted(qualifier.substring(colon + 1)));
            boolean required = false;
            boolean unique = false;
            ValueType type = null;
            String pattern = null;
            String derived = null;
            for (String flag : split(flags, ',')) {
                String words = plain(flag).toLowerCase(Locale.ROOT);
                Matcher letters = PATTERN_OF_LETTERS.matcher(words);
                Matcher digits = PATTERN_OF_DIGITS.matcher(flag.trim());
                Matcher derivation = DERIVED.matcher(flag.trim());
                if (words.isEmpty()) {
                    continue;
                } else if (derivation.matches()) {
                    derived = derivation.group(1);
                } else if (words.equals("required")) {
                    required = true;
                } else if (words.equals("unique")) {
                    unique = true;
                } else if (TYPE_WORDS.containsKey(words)) {
                    type = TYPE_WORDS.get(words);
                } else if (letters.matches()) {
                    pattern = "[A-Z]{" + COUNTS.get(letters.group(1)) + "}";
                } else if (digits.matches()) {
                    pattern = digits.group(1) + "[0-9]{" + COUNTS.get(digits.group(2)) + "}";
                } else {
                    throw new AssertionError("The test does not know the attribute detail '" + flag.trim()
                            + "' in: " + segment.trim());
                }
            }
            for (String name : names) {
                assertThat(attributes.put(name, new PageAttribute(type, required, unique, allowed, pattern, derived)))
                        .as("attribute %s listed once", name).isNull();
            }
        }
        return attributes;
    }

    @Test
    @DisplayName("The entity types of the page are those of the schema document")
    void entityTypesMatchThePage() {
        Table page = table("Entity type");
        Map<String, EntityTypeDefinition> types = model.entityTypes().stream()
                .collect(Collectors.toMap(EntityTypeDefinition::name, type -> type));
        assertThat(model.traits()).as("the page names no traits").isEmpty();
        assertThat(types.keySet()).containsExactlyInAnyOrderElementsOf(
                page.rows().stream().map(row -> plain(row.get("Entity type"))).toList());

        for (Map<String, String> row : page.rows()) {
            String name = plain(row.get("Entity type"));
            EntityTypeDefinition type = types.get(name);
            assertThat(type.isAbstract()).as("%s is abstract", name).isFalse();
            assertThat(type.supertype()).as("%s has a supertype", name).isEmpty();
            Map<String, PageAttribute> onPage = attributes(page.cell(row, "Attributes"));
            Map<String, AttributeDefinition> inDocument = type.attributes().stream()
                    .collect(Collectors.toMap(AttributeDefinition::name, attribute -> attribute));
            assertThat(inDocument.keySet()).as("the attributes of %s", name)
                    .containsExactlyInAnyOrderElementsOf(onPage.keySet());
            onPage.forEach((attribute, expected) -> {
                String where = name + "." + attribute;
                AttributeDefinition actual = inDocument.get(attribute);
                assertThat(actual.required()).as("%s required", where).isEqualTo(expected.required());
                assertThat(actual.unique()).as("%s unique", where).isEqualTo(expected.unique());
                assertThat(Set.copyOf(actual.allowedValues())).as("%s allowed values", where)
                        .isEqualTo(expected.allowedValues());
                assertThat(actual.pattern()).as("%s pattern", where).isEqualTo(Optional.ofNullable(expected.pattern()));
                assertThat(actual.derived().map(expression -> expression.replaceAll("\\s+", " ").trim()))
                        .as("%s derived from", where).isEqualTo(Optional.ofNullable(expected.derived()));
                if (expected.type() != null) {
                    assertThat(actual.valueType()).as("%s type", where).isEqualTo(expected.type());
                }
            });
        }
    }

    // ------------------------------------------------------------------ relationship types

    @Test
    @DisplayName("The relationship types of the page are those of the schema document")
    void relationshipTypesMatchThePage() {
        Table page = table("Relationship type");
        Map<String, RelationshipTypeDefinition> types = model.relationshipTypes().stream()
                .collect(Collectors.toMap(RelationshipTypeDefinition::name, type -> type));
        assertThat(types.keySet()).containsExactlyInAnyOrderElementsOf(
                page.rows().stream().map(row -> plain(row.get("Relationship type"))).toList());

        for (Map<String, String> row : page.rows()) {
            String name = plain(row.get("Relationship type"));
            RelationshipTypeDefinition type = types.get(name);
            assertThat(type.source()).as("%s source", name).isEqualTo(plain(page.cell(row, "Source")));
            assertThat(type.target()).as("%s target", name).isEqualTo(plain(page.cell(row, "Target")));
            assertThat(type.targetsPerSource()).as("%s targets per source", name)
                    .hasToString(plain(page.cell(row, "Targets per source")));
            assertThat(type.sourcesPerTarget()).as("%s sources per target", name)
                    .hasToString(plain(page.cell(row, "Sources per target")));
            assertThat(type.kind().name().toLowerCase(Locale.ROOT)).as("%s kind", name)
                    .isEqualTo(plain(page.cell(row, "Kind")));
            assertThat(type.inverseName()).as("%s inverse name", name).isEqualTo(plain(page.cell(row, "Inverse name")));
            assertThat(type.attributes()).as("%s has no attributes: the page lists none", name).isEmpty();
            assertThat(type.symmetric()).as("%s is symmetric", name).isFalse();
        }
    }

    // ------------------------------------------------------------------ rules

    @Test
    @DisplayName("The rules of the page are those of the schema document and of the rule classes")
    void rulesMatchThePage() {
        Table page = table("Rule");
        Map<String, RuleDescriptor> descriptors = new TreeMap<>();
        ServiceLoader.load(Rule.class).forEach(rule -> assertThat(descriptors.put(rule.descriptor().name(),
                rule.descriptor())).as("rule %s is provided once", rule.descriptor().name()).isNull());
        Map<String, List<String>> named = new TreeMap<>();
        for (EntityTypeDefinition type : model.entityTypes()) {
            type.rules().forEach(rule -> named.computeIfAbsent(rule.name(), k -> new ArrayList<>())
                    .add(type.name() + "@" + rule.version()));
        }
        for (RelationshipTypeDefinition type : model.relationshipTypes()) {
            type.rules().forEach(rule -> named.computeIfAbsent(rule.name(), k -> new ArrayList<>())
                    .add(type.name() + "@" + rule.version()));
        }
        List<String> onPage = page.rows().stream().map(row -> plain(row.get("Rule"))).toList();
        assertThat(descriptors.keySet()).as("the rules with code").containsExactlyInAnyOrderElementsOf(onPage);
        assertThat(named.keySet()).as("the rules the schema document names")
                .containsExactlyInAnyOrderElementsOf(onPage);

        for (Map<String, String> row : page.rows()) {
            String name = plain(row.get("Rule"));
            String type = plain(page.cell(row, "Type"));
            int version = Integer.parseInt(plain(page.cell(row, "Version")));
            RuleDescriptor descriptor = descriptors.get(name);
            assertThat(descriptor.version()).as("%s version", name).isEqualTo(version);
            assertThat(descriptor.appliesTo()).as("%s type", name).isEqualTo(type);
            assertThat(named.get(name)).as("the types that name %s", name).containsExactly(type + "@" + version);
            assertThat(plain(descriptor.description())).as("%s description", name)
                    .isEqualTo(plain(page.cell(row, "What it checks")));
            assertThat(descriptor.codes()).extracting(RuleCode::code).as("%s codes", name)
                    .containsExactlyInAnyOrderElementsOf(quoted(page.cell(row, "Code")));
        }
        assertThat(model.entityTypes().stream().flatMap(type -> type.rules().stream()).map(RuleReference::name))
                .as("rules named by entity types").isSubsetOf(onPage);
    }

    // ------------------------------------------------------------------ checks

    @Test
    @DisplayName("The checks of the page are those of the schema document")
    void checksMatchThePage() {
        Table page = table("Check");
        Map<String, List<String>> declaredBy = new TreeMap<>();
        Map<String, Check> checks = new TreeMap<>();
        for (EntityTypeDefinition type : model.entityTypes()) {
            for (Check check : type.checks()) {
                declaredBy.computeIfAbsent(check.name(), k -> new ArrayList<>()).add(type.name());
                checks.put(check.name(), check);
            }
        }
        for (RelationshipTypeDefinition type : model.relationshipTypes()) {
            for (Check check : type.checks()) {
                declaredBy.computeIfAbsent(check.name(), k -> new ArrayList<>()).add(type.name());
                checks.put(check.name(), check);
            }
        }
        List<String> onPage = page.rows().stream().map(row -> plain(row.get("Check"))).toList();
        assertThat(checks.keySet()).as("the checks the schema document declares")
                .containsExactlyInAnyOrderElementsOf(onPage);

        for (Map<String, String> row : page.rows()) {
            String name = plain(row.get("Check"));
            Check check = checks.get(name);
            assertThat(declaredBy.get(name)).as("the types that declare %s", name)
                    .containsExactly(plain(page.cell(row, "Type")));
            assertThat(plain(page.cell(row, "Expression"))).as("%s expression", name)
                    .isEqualTo(check.expression().replaceAll("\\s+", " ").trim());
            assertThat(plain(page.cell(row, "Message"))).as("%s message", name)
                    .isEqualTo(plain(check.message().orElse("")));
        }
    }

    // ------------------------------------------------------------------ people

    @Test
    @DisplayName("The people of the page are those of Brightwater and of the structure of HR-1")
    void peopleMatchBrightwater() {
        Table page = table("Employee");
        Map<String, Map<String, String>> rows = new TreeMap<>();
        page.rows().forEach(row -> assertThat(rows.put(plain(row.get("Employee")), row)).isNull());
        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(
                Brightwater.EMPLOYEES.stream().map(Brightwater.Employee::number).toList());

        Structure structure = structure();
        for (Brightwater.Employee employee : Brightwater.EMPLOYEES) {
            Map<String, String> row = rows.get(employee.number());
            String who = employee.number();
            assertThat(plain(page.cell(row, "Person"))).as("%s person", who)
                    .isEqualTo(employee.givenName() + " " + employee.familyName());

            Matcher position = POSITION.matcher(plain(page.cell(row, "Position")));
            assertThat(position.matches()).as("%s position '%s'", who, page.cell(row, "Position")).isTrue();
            assertThat(position.group(1)).as("%s position code", who)
                    .isEqualTo(structure.value(employee.position(), "code"));
            assertThat(position.group(2)).as("%s position title", who)
                    .isEqualTo(structure.value(employee.position(), "title"));
            assertThat(position.group(3)).as("%s job", who)
                    .isEqualTo(structure.value(structure.target("OfJob", employee.position()), "code"));

            String unit = structure.value(structure.target("BelongsTo", employee.position()), "code");
            assertThat(plain(page.cell(row, "Unit"))).as("%s unit", who).isEqualTo(unit);
            assertThat(structure.value(employee.unitCode(), "code")).as("%s unit of Brightwater", who).isEqualTo(unit);
            assertThat(plain(page.cell(row, "Manages"))).as("%s manages", who)
                    .isEqualTo(employee.manager() ? unit : "");

            assertThat(plain(page.cell(row, "Location"))).as("%s location", who)
                    .isEqualTo(structure.value(employee.location(), "name"));
            assertThat(structure.value(employee.employer(), "name")).as("%s employer", who)
                    .endsWith(plain(page.cell(row, "Employer")));

            Matcher salary = SALARY.matcher(plain(page.cell(row, "Salary")));
            assertThat(salary.matches()).as("%s salary '%s'", who, page.cell(row, "Salary")).isTrue();
            assertThat(new BigDecimal(salary.group(1).replace(",", ""))).as("%s salary", who)
                    .isEqualByComparingTo(employee.salary());
            assertThat(salary.group(2)).as("%s currency", who).isEqualTo("EUR");
        }
    }

    /** The creates and relationships of the HR-1 payload, found by their temporary IDs. */
    private record Structure(Map<String, Write.Create> creates, List<Write.Relate> relates) {

        String value(String temporaryId, String attribute) {
            Write.Create create = creates.get(temporaryId);
            assertThat(create).as("the structure creates $%s", temporaryId).isNotNull();
            return ((Value.StringValue) create.values().get(attribute)).value();
        }

        /** The temporary ID at the other end of the only relationship of a type from the given one. */
        String target(String relationshipType, String source) {
            List<String> targets = relates.stream()
                    .filter(relate -> relate.relationshipType().equals(relationshipType)
                            && relate.source().equals(new TemporaryId(source)))
                    .map(relate -> ((TemporaryId) relate.target()).name()).toList();
            assertThat(targets).as("%s from $%s", relationshipType, source).hasSize(1);
            return targets.getFirst();
        }
    }

    private static Structure structure() {
        Store store = Stores.inMemory(StoreSettings.builder().discoverRules().build());
        var document = Brightwater.schemaDocument();
        store.schema().register(document.origin(), document.definition(), Submission.by(Brightwater.HR_ADMIN));
        Map<String, Write.Create> creates = new LinkedHashMap<>();
        List<Write.Relate> relates = new ArrayList<>();
        for (Write write : Brightwater.hr1Structure(store.schema().current()).transaction().writes()) {
            if (write instanceof Write.Create create) {
                create.temporaryId().ifPresent(id -> creates.put(id.name(), create));
            } else if (write instanceof Write.Relate relate) {
                relates.add(relate);
            }
        }
        return new Structure(creates, relates);
    }
}
