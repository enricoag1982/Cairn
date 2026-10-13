/**
 * The Gradle plugin that generates Java types from a project's schema documents (ADR-0021, capability
 * {@code java-types}).
 *
 * <p>The plugin, {@code io.github.enricoag1982.cairn.types}, runs on the Java that runs Gradle, which may be older than
 * the Java that Cairn needs. It therefore has no dependency on Cairn's classes: it runs the generator, which needs
 * Java 25, in a process of its own.
 */
package io.github.enricoag1982.cairn.gradle;
