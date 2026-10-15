import org.gradle.api.provider.Property

/**
 * The settings of the convention plugin cairn.generated-types: what a module says about the types generated from its
 * schema documents.
 */
abstract class GeneratedTypesExtension {

    /** The package of the generated Java types. */
    abstract val packageName: Property<String>
}
