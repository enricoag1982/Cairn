/**
 * The interface between the engine and an expression language that runs checks and derivations (ADR-0030 and ADR-0031,
 * capabilities {@code checks} and {@code derived-attributes}).
 *
 * <p>Entity types and relationship types declare {@linkplain io.github.enricoag1982.cairn.schema.Check checks}, which
 * are expressions over one version of their values, and attributes may be derived, with an expression over the other
 * values of the version. The engine does not read the expressions itself: an
 * {@link io.github.enricoag1982.cairn.expressions.ExpressionLanguage} compiles each check into a
 * {@link io.github.enricoag1982.cairn.expressions.CompiledCheck}, and each derivation into a
 * {@link io.github.enricoag1982.cairn.expressions.CompiledDerivation}, which the engine evaluates for every version that
 * a transaction writes. The module {@code cairn-cel} provides the Common Expression Language, and the engine finds a
 * language through Java's {@link java.util.ServiceLoader} or from its settings.
 */
package io.github.enricoag1982.cairn.expressions;
