package io.github.enricoag1982.cairn.cel;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.StructType;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import io.github.enricoag1982.cairn.expressions.CheckCompilationException;
import io.github.enricoag1982.cairn.expressions.CompiledCheck;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.schema.Check;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The Common Expression Language as the language of checks (ADR-0030, design D3 of {@code add-cel-expressions}), on
 * Google's CEL for Java.
 *
 * <p>An expression sees one variable, {@code self}: the values of one version. {@code self} is declared as a struct with
 * one field for each attribute of the type, typed from the attribute's value type, so that the checker finds an unknown
 * attribute, and a comparison of a number with a string, when the check is compiled, with the position in the expression.
 * No protobuf message is involved; at evaluation {@code self} is a map from the names of the attributes that have a value
 * to those values, so that {@code has(self.name)} tells whether an attribute has a value and reading one without a value
 * is an evaluation error. These are the CEL types of the value types:
 *
 * <table class="striped">
 *   <caption>The CEL types of the value types</caption>
 *   <thead><tr><th scope="col">Value type</th><th scope="col">CEL type</th></tr></thead>
 *   <tbody>
 *     <tr><td>string, and an attribute with allowed values</td><td>{@code string}</td></tr>
 *     <tr><td>boolean</td><td>{@code bool}</td></tr>
 *     <tr><td>int, long</td><td>{@code int}</td></tr>
 *     <tr><td>decimal</td><td>{@code double}</td></tr>
 *     <tr><td>date</td><td>{@code timestamp} at 00:00 UTC of the day</td></tr>
 *     <tr><td>instant</td><td>{@code timestamp}</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>The result of an expression must be {@code bool} or {@code dyn}. CEL does not mix integers and doubles in an
 * operator that the checker types, so a decimal is compared with {@code 0.0}, not with {@code 0}. The standard functions
 * and macros are available, and no extension.
 *
 * <p>Evaluation is bounded: it stops after {@value #COST_LIMIT} evaluation steps, one for each node of the expression that
 * is evaluated, a node of a comprehension once for each element it visits, which is an evaluation error. CEL for Java has
 * no tracking of CEL's own cost units, so the steps are counted here.
 *
 * <p>An instance is thread-safe and compiles for any number of types.
 */
public final class CelLanguage implements ExpressionLanguage {

    /** The most evaluation steps that one evaluation of a check may take. */
    public static final int COST_LIMIT = 10_000;

    /** The name of the struct type of {@code self}. */
    private static final String SELF_TYPE = "cairn.Self";

    private static final CelOptions OPTIONS = CelOptions.current()
            .maxExpressionCodePointSize(Check.MAXIMUM_EXPRESSION_LENGTH)
            .build();

    /** One CEL environment for each set of attributes: it compiles any number of expressions. */
    private final ConcurrentMap<Map<String, ValueType>, Cel> environments = new ConcurrentHashMap<>();

    /** Creates the language; the service loader calls it. */
    public CelLanguage() {
    }

    @Override
    public String name() {
        return "cel";
    }

    @Override
    public CompiledCheck compile(String expression, Map<String, ValueType> attributes)
            throws CheckCompilationException {
        Objects.requireNonNull(expression, "expression");
        Map<String, ValueType> types = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        Cel cel = environments.computeIfAbsent(types, CelLanguage::environment);
        CelValidationResult validation = cel.compile(expression);
        if (validation.hasError()) {
            throw firstError(validation);
        }
        try {
            CelAbstractSyntaxTree ast = validation.getAst();
            CelKind kind = ast.getResultType().kind();
            if (kind != CelKind.BOOL && kind != CelKind.DYN) {
                throw resultNotBoolean(ast);
            }
            CelRuntime.Program program = cel.createProgram(ast);
            return new CelCheck(program, types);
        } catch (CelValidationException | CelEvaluationException e) {
            throw new CheckCompilationException(e.getMessage());
        }
    }

    /** The CEL environment in which {@code self} is a struct with a field for each attribute. */
    private static Cel environment(Map<String, ValueType> attributes) {
        StructType self = StructType.create(SELF_TYPE, ImmutableSet.copyOf(attributes.keySet()),
                field -> Optional.ofNullable(attributes.get(field)).map(Values::celType));
        CelTypeProvider provider = new CelTypeProvider() {
            @Override
            public ImmutableCollection<CelType> types() {
                return ImmutableSet.of(self);
            }

            @Override
            public Optional<CelType> findType(String name) {
                return SELF_TYPE.equals(name) ? Optional.of(self) : Optional.empty();
            }
        };
        return CelFactory.standardCelBuilder()
                .setOptions(OPTIONS)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .setTypeProvider(provider)
                .addVar(CelCheck.SELF, self)
                .build();
    }

    /** The first error of a failed compilation, with its position: lines and columns of CEL start at 0 for columns. */
    private static CheckCompilationException firstError(CelValidationResult validation) {
        CelIssue issue = validation.getErrors().getFirst();
        return exception(issue.getMessage(), issue.getSourceLocation());
    }

    private static CheckCompilationException resultNotBoolean(CelAbstractSyntaxTree ast) {
        String message = "The result of the expression is " + article(ast.getResultType().name()) + ", but the result of"
                + " a check must be a boolean.";
        Integer offset = ast.getSource().getPositionsMap().get(ast.getExpr().id());
        return offset == null ? new CheckCompilationException(message)
                : ast.getSource().getOffsetLocation(offset).map(location -> exception(message, location))
                        .orElseGet(() -> new CheckCompilationException(message));
    }

    /** A line counts from 1 and a column, in CEL, from 0 in code points; an exception counts both from 1. */
    private static CheckCompilationException exception(String message, CelSourceLocation location) {
        if (location.getLine() < 1 || location.getColumn() < 0) {
            return new CheckCompilationException(message);
        }
        return new CheckCompilationException(message, location.getLine(), location.getColumn() + 1);
    }

    private static String article(String type) {
        return (type.startsWith("a") || type.startsWith("e") || type.startsWith("i") || type.startsWith("o")
                || type.startsWith("u") ? "an " : "a ") + type;
    }
}
