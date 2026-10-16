package io.github.enricoag1982.cairn.cel;

import dev.cel.common.ast.CelExpr;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds the attributes that a checked expression reads from {@code self} (design D1 of
 * {@code add-attribute-access-control}): every field that it selects from {@code self}, also as the test of
 * {@code has(self.name)}, in the whole tree, comprehensions included.
 *
 * <p>An expression that uses {@code self} in any other way, such as {@code dyn(self)['name']}, can read an attribute
 * that the tree does not name, so it is taken to read every attribute that the expression was compiled for. A name
 * that a comprehension variable called {@code self} shadows is counted as well: the result may be larger than what an
 * evaluation reads, never smaller.
 */
final class SelfReads {

    private final Set<String> attributes;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean escapes;

    private SelfReads(Set<String> attributes) {
        this.attributes = attributes;
    }

    /**
     * The attributes an expression reads.
     *
     * @param expression the checked expression
     * @param attributes the names of the attributes it was compiled for
     * @return the attributes it reads, in the order in which the tree names them; all of {@code attributes} if it uses
     *         {@code self} other than to select a field of it
     */
    static Set<String> of(CelExpr expression, Set<String> attributes) {
        SelfReads reads = new SelfReads(attributes);
        reads.walk(expression);
        return Set.copyOf(reads.escapes ? attributes : reads.selected);
    }

    private void walk(CelExpr expr) {
        switch (expr.getKind()) {
            case IDENT -> {
                if (Evaluation.SELF.equals(expr.ident().name())) {
                    escapes = true;
                }
            }
            case SELECT -> {
                CelExpr operand = expr.select().operand();
                if (isSelf(operand)) {
                    if (attributes.contains(expr.select().field())) {
                        selected.add(expr.select().field());
                    }
                } else {
                    walk(operand);
                }
            }
            case CALL -> {
                expr.call().target().ifPresent(this::walk);
                expr.call().args().forEach(this::walk);
            }
            case LIST -> expr.list().elements().forEach(this::walk);
            case STRUCT -> expr.struct().entries().forEach(entry -> walk(entry.value()));
            case MAP -> expr.map().entries().forEach(entry -> {
                walk(entry.key());
                walk(entry.value());
            });
            case COMPREHENSION -> {
                CelExpr.CelComprehension comprehension = expr.comprehension();
                walk(comprehension.iterRange());
                walk(comprehension.accuInit());
                walk(comprehension.loopCondition());
                walk(comprehension.loopStep());
                walk(comprehension.result());
            }
            case CONSTANT, NOT_SET -> {
            }
        }
    }

    private static boolean isSelf(CelExpr expr) {
        return expr.getKind() == CelExpr.ExprKind.Kind.IDENT && Evaluation.SELF.equals(expr.ident().name());
    }
}
