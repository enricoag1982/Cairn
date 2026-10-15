package io.github.enricoag1982.cairn.documents;

import io.github.enricoag1982.cairn.query.QueryResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads queries written as JSON or YAML, and writes the results of queries as JSON (capability {@code queries}, design
 * D4 of {@code add-queries-and-aggregation}).
 *
 * <p>A query is YAML 1.2, or JSON, which is valid YAML 1.2: a mapping that mirrors the records of
 * {@link io.github.enricoag1982.cairn.query.Query} one to one, with the keys
 *
 * <ul>
 * <li>{@code from}: {@code {type, as, exact?}}, the entity type, the name each entity is bound to, and whether only the
 *     exact type is taken;
 * <li>{@code steps}: a list of steps, each a mapping with one key: {@code where} (a condition), {@code follow} or
 *     {@code optionalFollow} ({@code {from, name, as, relationship?}}, the binding to lead away from, the relationship
 *     name or inverse name, the name for the entity at the other end, and the name for the relationship), or
 *     {@code traverse} ({@code {from, name, as, minDepth?, maxDepth, relationship?, depth?, along?}}, the minimum
 *     depth being 0 if it is left out, and {@code along} a condition on the relationship, which must then be named
 *     with {@code relationship}, that decides which relationships the traversal follows);
 * <li>{@code groupBy}: group keys, {@code {of, attribute?, as?}}, a binding or an attribute of it, and the name of the
 *     column;
 * <li>{@code aggregates}: {@code {function, attribute?, of?, as}}, with {@code function} one of {@code count},
 *     {@code sum}, {@code min}, {@code max} and {@code avg};
 * <li>{@code columns}: each a mapping with one of {@code binding}, {@code attribute} (with {@code of}), {@code depth},
 *     {@code key} and {@code aggregate}, and optionally {@code as}, the name of the column;
 * <li>{@code order}: the same, without {@code as} and with {@code descending?};
 * <li>{@code limit}: the largest number of rows.
 * </ul>
 *
 * <p>A condition is a mapping with one key: {@code compare} ({@code {left, op, right}}, {@code op} being one of
 * {@code eq}, {@code ne}, {@code lt}, {@code le}, {@code gt} and {@code ge}), {@code in} ({@code {operand, values}}),
 * {@code hasValue} ({@code {attribute, of}}), {@code hasLink} ({@code {of, name, relationship?, along?}}, where
 * {@code along}, which needs {@code relationship}, the name it refers to the relationship by, is a condition that the
 * relationship of a link must meet), {@code isA} ({@code {of, type}}), {@code and} and {@code or} (lists of
 * conditions) and {@code not} (a condition). An operand is
 * {@code {attribute, of}}, {@code {id: <binding>}}, {@code {type: <binding>}} or {@code {value: <literal>}}.
 *
 * <p>The keys {@code cairn}, the format version, which must be 1 if it is there, and {@code $schema}, which names the
 * published JSON Schema for editors, are accepted too; reading ignores {@code $schema}. Numbers are written as JSON
 * writes them.
 */
public final class Queries {

    private static final String JSON_SCHEMA_RESOURCE = "query-1.schema.json";

    private Queries() {
    }

    /**
     * Reads a query.
     *
     * <p>Reading is strict, like reading a schema document or a transaction payload. It reports every problem it finds,
     * each with its location in the text, and returns no query if there is one: an unknown key (located at the key, with
     * the closest known key as a hint), a missing key that is required, a value of the wrong kind or form (a number
     * written in a form that only YAML knows, a negative limit, a maximum depth below the minimum, a step with no key or
     * with two), and YAML features outside the supported subset (anchors, aliases, explicit tags, merge keys, duplicate
     * keys and more than one document). A YAML syntax error stops reading and is the only problem reported. A format
     * version other than 1 also ends reading with that problem alone. Reading does not throw.
     *
     * <p>The paths of the problems of reading are those of the problems of running a query: {@code @query} for the query
     * as a whole, {@code @query.from}, {@code @query.steps.<n>}, {@code @query.groupBy.<n>},
     * {@code @query.aggregates.<n>}, {@code @query.columns.<n>}, {@code @query.order.<n>} and {@code @query.limit}.
     *
     * <p>Literals are read as payloads read a value of an unknown type: a JSON number is an int, a long or a decimal, a
     * string is a string, and {@code true} and {@code false} are booleans. The engine takes a string that spells a date, or
     * an instant in the form of payloads, as one where the literal is compared with a date or an instant attribute.
     * Reading does not check names against a schema: the view that runs the query does, and the problems it returns can
     * be located with {@link LocatedQuery#locate(java.util.List)}.
     *
     * @param text the query, as YAML or JSON
     * @return the query with its locations, or the problems
     */
    public static QueryReadResult read(String text) {
        return QueryReader.read(Objects.requireNonNull(text, "text"));
    }

    /**
     * Writes the result of a query as JSON, with two-space indentation and a line break at the end.
     *
     * <p>Rows are written as {@code {effectiveAt, basis, columns, rows}}: the effective time as an ISO 8601 instant, the
     * basis as a number, the names of the columns, and each row as a list of cells in the order of the columns. A cell is
     * the state of an entity or of a relationship in the forms of {@link Payloads#toJson(io.github.enricoag1982.cairn.data.EntityState)}
     * and its sibling, a value as schema documents write literals (numbers as JSON numbers, booleans, and strings, dates
     * and instants as strings), or {@code null} for an empty cell. An invalid result is written as the list of its
     * problems, as {@link Payloads#problemsToJson(java.util.List)} writes them.
     *
     * @param result the result
     * @return the JSON text
     */
    public static String toJson(QueryResult result) {
        return JsonText.render(JsonResults.queryResult(Objects.requireNonNull(result, "result")));
    }

    /**
     * Returns the published JSON Schema (draft 2020-12) that describes queries of format version 1.
     *
     * <p>Its {@code $id} is {@code urn:cairn:query:1}. Every query that {@link #read(String)} reads without problems is
     * valid against it, as JSON or as YAML converted to JSON; a query with an unknown key, a missing required key or a
     * value of the wrong kind is invalid. It does not check what only a view knows: names of types, attributes,
     * relationships and bindings.
     *
     * @return the JSON Schema, as JSON text
     */
    public static String jsonSchema() {
        try (InputStream stream = Queries.class.getResourceAsStream(JSON_SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The resource " + JSON_SCHEMA_RESOURCE + " is missing from the module.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The resource " + JSON_SCHEMA_RESOURCE + " cannot be read.", e);
        }
    }
}
