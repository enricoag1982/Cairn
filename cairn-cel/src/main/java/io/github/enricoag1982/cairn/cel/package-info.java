/**
 * The Common Expression Language (CEL) for the checks and the derived attributes of schema definitions (ADR-0030 and
 * ADR-0031, capabilities {@code checks} and {@code derived-attributes}): an
 * {@link io.github.enricoag1982.cairn.expressions.ExpressionLanguage} on Google's CEL for Java ({@code dev.cel:cel}), which
 * the engine finds through Java's {@link java.util.ServiceLoader} when this module is on the class path. Besides CEL's
 * standard functions, expressions can call functions of Cairn's own, named {@code cairn.<name>} (ADR-0035):
 * {@code cairn.calendarDays} and {@code cairn.workingDays}.
 *
 * <p>The module has no module descriptor. The jars of CEL for Java are split across several jars that share packages and
 * have no module names of their own, so they cannot be resolved as modules; the module, like its dependencies, is on the
 * class path, and an application that runs Cairn's modules on the module path adds it to the class path beside them.
 * It brings Guava, protobuf-java, the ANTLR runtime and others with it.
 */
package io.github.enricoag1982.cairn.cel;
