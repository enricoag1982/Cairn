package io.github.enricoag1982.cairn.core;

import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Finds the expression language that runs checks and derivations (design D2 of {@code add-cel-expressions}, ADR-0030
 * and ADR-0031): the first one that Java's {@link ServiceLoader} finds, on the class path or the module path, such as the
 * one of the module {@code cairn-cel}.
 */
final class ExpressionLanguages {

    private ExpressionLanguages() {
    }

    /**
     * Looks for an expression language.
     *
     * @return the first one found, if any
     * @throws java.util.ServiceConfigurationError if a provider cannot be loaded
     */
    static Optional<ExpressionLanguage> discovered() {
        return ServiceLoader.load(ExpressionLanguage.class).findFirst();
    }
}
