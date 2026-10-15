package io.github.enricoag1982.cairn.expressions;

import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;

/**
 * A language in which checks are written (ADR-0030, capability {@code checks}), such as the Common Expression Language
 * of the module {@code cairn-cel}.
 *
 * <p>An engine compiles each check of a schema definition once, for each type it applies to, and evaluates the compiled
 * check for every version that a transaction writes. The language sees one variable, {@code self}: a map from the
 * names of the attributes of the version that have a value to those values. An attribute without a value is absent from
 * the map.
 *
 * <p>An expression language must be deterministic and free of side effects: it gives the same result for the same
 * values with every storage implementation, it reads nothing but the values it is given, and it bounds the work that an
 * evaluation does. It must be thread-safe, and so must the checks it compiles: stores made from the same settings share
 * the language and call it from different threads.
 *
 * <p>An engine finds a language through Java's {@link java.util.ServiceLoader}, which needs a public class with a
 * public constructor without parameters, or a public static {@code provider()} method, and a {@code provides} clause in
 * the module or a file in {@code META-INF/services}.
 */
public interface ExpressionLanguage {

    /**
     * The name of the language, for example {@code cel}.
     *
     * @return the name, never blank
     */
    String name();

    /**
     * Compiles the expression of a check against the attributes of a type.
     *
     * @param expression the expression, not blank
     * @param attributes the value types of the attributes of the type, including inherited ones, by attribute name;
     *                   the expression may read these through {@code self}, and no others
     * @return the compiled check, which can be evaluated for many versions and by many threads
     * @throws CheckCompilationException if the expression is not valid in the language, reads an attribute that the
     *                                   type does not have, or has a result that cannot be a boolean
     */
    CompiledCheck compile(String expression, Map<String, ValueType> attributes) throws CheckCompilationException;
}
