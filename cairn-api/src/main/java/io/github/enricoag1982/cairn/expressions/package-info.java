/**
 * The interface between the engine and an expression language that runs checks (ADR-0030, capability {@code checks}).
 *
 * <p>Entity types and relationship types declare {@linkplain io.github.enricoag1982.cairn.schema.Check checks}, which
 * are expressions over one version of their values. The engine does not read the expressions itself: an
 * {@link io.github.enricoag1982.cairn.expressions.ExpressionLanguage} compiles each of them into a
 * {@link io.github.enricoag1982.cairn.expressions.CompiledCheck}, which the engine evaluates for every version that a
 * transaction writes. The module {@code cairn-cel} provides the Common Expression Language, and the engine finds a
 * language through Java's {@link java.util.ServiceLoader} or from its settings.
 */
package io.github.enricoag1982.cairn.expressions;
