package io.github.enricoag1982.cairn.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;

/**
 * The settings of the plugin, in the {@code cairn} block of a build script:
 *
 * <pre>
 * cairn {
 *     packageName = "com.example.model"
 *     schemaDocuments.from("src/main/model/crm.cairn.yaml")
 * }
 * </pre>
 */
public abstract class CairnExtension {

    /**
     * Creates the extension; Gradle does, as {@code cairn}.
     */
    public CairnExtension() {
    }

    /**
     * The schema documents to generate types for, YAML or JSON. By default they are the files that end in
     * {@code .cairn.yaml} or {@code .cairn.json} in {@code src/main/cairn}, and its subdirectories. Setting the
     * collection with {@code from} adds to the default; use {@code setFrom} to replace it.
     *
     * @return the documents
     */
    public abstract ConfigurableFileCollection getSchemaDocuments();

    /**
     * The package of the generated types, such as {@code com.example.model}. It is required.
     *
     * @return the package name
     */
    public abstract Property<String> getPackageName();
}
