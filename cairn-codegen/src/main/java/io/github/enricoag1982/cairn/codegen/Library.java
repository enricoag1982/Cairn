package io.github.enricoag1982.cairn.codegen;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The library types that generated code refers to, by qualified name. Generated files import them, or write their
 * qualified names where a type of the documents has the same simple name ({@link Imports}).
 */
final class Library {

    static final String BIG_DECIMAL = "java.math.BigDecimal";
    static final String LOCAL_DATE = "java.time.LocalDate";
    static final String INSTANT = "java.time.Instant";
    static final String LIST = "java.util.List";
    static final String LINKED_HASH_MAP = "java.util.LinkedHashMap";
    static final String LINKED_HASH_SET = "java.util.LinkedHashSet";
    static final String MAP = "java.util.Map";
    static final String OPTIONAL = "java.util.Optional";
    static final String SET = "java.util.Set";

    static final String STRING = "java.lang.String";
    static final String BOOLEAN = "java.lang.Boolean";
    static final String INTEGER = "java.lang.Integer";
    static final String LONG = "java.lang.Long";
    static final String DEPRECATED = "java.lang.Deprecated";
    static final String OVERRIDE = "java.lang.Override";
    static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";
    static final String ILLEGAL_ARGUMENT_EXCEPTION = "java.lang.IllegalArgumentException";

    private static final String DATA = "io.github.enricoag1982.cairn.data.";
    private static final String SCHEMA = "io.github.enricoag1982.cairn.schema.";
    private static final String QUERY = "io.github.enricoag1982.cairn.query.";

    static final String CONFLICT_CHECK = DATA + "ConflictCheck";
    static final String EFFECTIVE_PERIOD = DATA + "EffectivePeriod";
    static final String ENTITY_ID = DATA + "EntityId";
    static final String ENTITY_RULE = DATA + "EntityRule";
    static final String ENTITY_STATE = DATA + "EntityState";
    static final String REF = DATA + "Ref";
    static final String RELATIONSHIP_ID = DATA + "RelationshipId";
    static final String RELATIONSHIP_RULE = DATA + "RelationshipRule";
    static final String RELATIONSHIP_STATE = DATA + "RelationshipState";
    static final String RULE_CONTEXT = DATA + "RuleContext";
    static final String TEMPORARY_ID = DATA + "TemporaryId";
    static final String TYPED_ENTITY = DATA + "TypedEntity";
    static final String TYPED_RELATIONSHIP = DATA + "TypedRelationship";
    static final String TYPED_VALUES = DATA + "TypedValues";
    static final String UNIQUE_KEY = DATA + "UniqueKey";
    static final String WRITE = DATA + "Write";
    static final String SCHEMA_TYPE = SCHEMA + "Schema";
    static final String RULE_CODE = SCHEMA + "RuleCode";
    static final String RULE_DESCRIPTOR = SCHEMA + "RuleDescriptor";
    static final String VALUE = SCHEMA + "Value";
    static final String ATTRIBUTE_REF = QUERY + "AttributeRef";
    static final String ORDERED_ATTRIBUTE_REF = QUERY + "OrderedAttributeRef";
    static final String NUMERIC_ATTRIBUTE_REF = QUERY + "NumericAttributeRef";

    /**
     * The simple names of the types that generated code uses before a dot, such as {@code Optional.empty()}. A
     * variable or a component with such a name would hide the type there, so attributes are not given these names.
     */
    static final Set<String> QUALIFIERS = Set.of(BIG_DECIMAL, LOCAL_DATE, INSTANT, LIST, LINKED_HASH_MAP,
            LINKED_HASH_SET, MAP, OPTIONAL, SET, STRING, BOOLEAN, INTEGER, LONG, CONFLICT_CHECK, EFFECTIVE_PERIOD,
            ENTITY_ID, ENTITY_STATE, REF, RELATIONSHIP_ID, RELATIONSHIP_STATE, TEMPORARY_ID, TYPED_VALUES, WRITE, VALUE,
            ATTRIBUTE_REF)
            .stream().map(Library::simpleName).collect(Collectors.toUnmodifiableSet());

    private Library() {
    }

    static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    static String packageOf(String qualified) {
        return qualified.substring(0, qualified.lastIndexOf('.'));
    }
}
