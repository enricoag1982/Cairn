package io.github.enricoag1982.cairn.codegen;

import io.github.enricoag1982.cairn.schema.Attribute;
import io.github.enricoag1982.cairn.schema.ValueType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the Java source of a trait, an entity type or a relationship type (design D3 of
 * {@code add-java-type-generation}): the interface with its accessors, and, for entity types and relationship types,
 * {@code from}, the snapshot record and the builders; for entity types also a {@code by<Attribute>} method for each unique
 * attribute, which makes a unique key (design D7 of {@code add-unique-key-references}). The accessors, the snapshot record
 * and the typed references include the derived attributes, which say that they are derived and give their expression; the
 * builders of writes have no setter for them (design D7 of {@code add-derived-attributes}).
 */
final class TypeSource {

    /** The names of the classes that an interface declares itself. */
    private static final Set<String> NESTED = Set.of("Snapshot", "Create", "Patch", "Replace", "Relate");

    private final Model model;
    private final Element element;
    private final Imports imports;
    private final Source out = new Source();
    private final boolean relationship;
    private boolean memberWritten;

    private TypeSource(Model model, Element element) {
        this.model = model;
        this.element = element;
        this.imports = new Imports(model.types());
        this.relationship = element.kind() == Element.Kind.RELATIONSHIP;
    }

    /** The source file of an element. */
    static String of(Model model, Element element) {
        TypeSource source = new TypeSource(model, element);
        source.writeInterface();
        return SourceFile.assemble(model, source.imports, source.out);
    }

    // ------------------------------------------------------------------ names

    private String ref(String library) {
        return imports.ref(library);
    }

    /** Refers to a type of the documents: by its simple name, unless one of its own nested classes could hide it. */
    private String type(Element other) {
        return NESTED.contains(other.java()) ? model.packageName() + "." + other.java() : other.java();
    }

    private String type(String name) {
        return type(model.element(name));
    }

    /** The name of a class that the interface of an element declares: its own, with a suffix if the type has the name. */
    private static String nested(Element owner, String name) {
        return owner.java().equals(name) ? name + "_" : name;
    }

    private String attribute(Attribute attribute) {
        return model.names().attribute(element.nameKind(), attribute);
    }

    private String scalar(ValueType type) {
        return switch (type) {
            case STRING -> ref(Library.STRING);
            case BOOLEAN -> "boolean";
            case INT -> "int";
            case LONG -> "long";
            case DECIMAL -> ref(Library.BIG_DECIMAL);
            case DATE -> ref(Library.LOCAL_DATE);
            case INSTANT -> ref(Library.INSTANT);
        };
    }

    private String boxed(ValueType type) {
        return switch (type) {
            case BOOLEAN -> ref(Library.BOOLEAN);
            case INT -> ref(Library.INTEGER);
            case LONG -> ref(Library.LONG);
            default -> scalar(type);
        };
    }

    /** The Java type of an attribute: its value type if it is required, else an {@code Optional} of it. */
    private String javaType(Attribute attribute) {
        return attribute.required() ? scalar(attribute.valueType())
                : ref(Library.OPTIONAL) + "<" + boxed(attribute.valueType()) + ">";
    }

    /** The method of {@code TypedValues} that reads an attribute: {@code requiredInt}, {@code optionalDate} and so on. */
    private static String reader(Attribute attribute) {
        String type = switch (attribute.valueType()) {
            case STRING -> "String";
            case BOOLEAN -> "Boolean";
            case INT -> "Int";
            case LONG -> "Long";
            case DECIMAL -> "Decimal";
            case DATE -> "Date";
            case INSTANT -> "Instant";
        };
        return (attribute.required() ? "required" : "optional") + type;
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------ writing

    /** Starts a member: a blank line separates it from the one before. */
    private void member() {
        if (memberWritten) {
            out.blank();
        }
        memberWritten = true;
    }

    /** The description that the document gives, or else a sentence of our own: a blank description says nothing. */
    private static String summary(Optional<String> description, String generated) {
        return description.filter(text -> !text.isBlank()).map(Docs::text).orElse(generated);
    }

    private static List<String> doc(String summary, List<String> paragraphs, List<String> tags) {
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        for (String paragraph : paragraphs) {
            lines.add("");
            lines.add(paragraph);
        }
        if (!tags.isEmpty()) {
            lines.add("");
            lines.addAll(tags);
        }
        return lines;
    }

    private static List<String> doc(String summary, String... tags) {
        return doc(summary, List.of(), List.of(tags));
    }

    private String things() {
        return relationship ? "relationships" : "entities";
    }

    private String thing() {
        return relationship ? "relationship" : "entity";
    }

    private String state() {
        return ref(relationship ? Library.RELATIONSHIP_STATE : Library.ENTITY_STATE);
    }

    private String stateMethod() {
        return relationship ? "relationshipState" : "entityState";
    }

    private void writeInterface() {
        String kindLabel = element.kind().label();
        List<String> paragraphs = new ArrayList<>();
        if (relationship) {
            paragraphs.add("<p>Relates " + a(element.source()) + " (the source) to " + a(element.target())
                    + " (the target).");
        }
        List<String> tags = new ArrayList<>();
        if (element.deprecated()) {
            tags.add("@deprecated This " + kindLabel + " is deprecated in its schema document.");
        }
        out.javadoc(doc(summary(element.description(), "The " + kindLabel + " " + Docs.code(element.name()) + "."),
                paragraphs, tags));
        if (element.deprecated()) {
            out.line("@" + ref(Library.DEPRECATED));
        }
        if (model.deprecations()) {
            out.line("@" + ref(Library.SUPPRESS_WARNINGS) + "(\"deprecation\")");
        }
        List<String> parents = new ArrayList<>();
        element.supertype().ifPresent(supertype -> parents.add(type(supertype)));
        element.traits().forEach(trait -> parents.add(type(trait)));
        if (parents.isEmpty()) {
            parents.add(ref(relationship ? Library.TYPED_RELATIONSHIP : Library.TYPED_ENTITY));
        }
        out.open("public interface " + element.java() + " extends " + String.join(", ", parents) + " {");
        out.blank();
        if (element.kind() != Element.Kind.TRAIT) {
            writeType();
        }
        if (relationship) {
            writeInverse();
        }
        for (Attribute attribute : element.ownAttributes()) {
            writeReference(attribute);
        }
        for (Attribute attribute : element.ownAttributes()) {
            writeAccessor(attribute);
        }
        if (element.kind() != Element.Kind.TRAIT) {
            writeFrom();
            writeFromInSchema();
            writeKeys();
            writeFactories();
            writeSnapshot();
            if (relationship) {
                writeRelateBuilder();
            } else if (!element.isAbstract()) {
                writeCreateBuilder();
            }
            writePatch();
            writeReplace();
        }
        out.close("}");
    }

    private void writeType() {
        member();
        out.javadoc(List.of("The name of this " + element.kind().label() + " in its schema."));
        out.line(ref(Library.STRING) + " TYPE = " + Docs.literal(element.name()) + ";");
    }

    /**
     * The inverse name of a relationship type, as a constant next to {@code TYPE} (design D5 of
     * {@code add-query-and-traversal-conveniences}).
     */
    private void writeInverse() {
        member();
        out.javadoc(List.of("The inverse name of this relationship type in its schema: the name that leads from a "
                + "target to its sources."));
        out.line(ref(Library.STRING) + " INVERSE = " + Docs.literal(element.inverse()) + ";");
    }

    /**
     * The typed reference of an attribute that this type declares, as a constant (design D6 of
     * {@code add-queries-and-aggregation}): it builds the conditions, columns, group keys, orders and aggregates of the
     * attribute for a binding of a query, with the Java type of the attribute's values.
     */
    private void writeReference(Attribute attribute) {
        member();
        String name = model.constant(element, attribute);
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("<p>Give it the name of a binding of a query with <code>of</code>: for example "
                + "<code>" + name + ".of(\"x\")</code> is the attribute of the binding <code>x</code>.");
        attribute.description().filter(text -> !text.isBlank())
                .ifPresent(text -> paragraphs.add("<p>" + Docs.text(text)));
        paragraphs.addAll(Docs.derived(attribute));
        List<String> tags = new ArrayList<>();
        if (attribute.deprecated()) {
            tags.add("@deprecated The attribute " + Docs.code(attribute.name())
                    + " is deprecated in its schema document.");
        }
        out.javadoc(doc("A typed reference to the attribute " + Docs.code(attribute.name()) + ", for building queries.",
                paragraphs, tags));
        if (attribute.deprecated()) {
            out.line("@" + ref(Library.DEPRECATED));
        }
        String factory = switch (attribute.valueType()) {
            case STRING -> "string";
            case BOOLEAN -> "bool";
            case INT -> "integer";
            case LONG -> "longInteger";
            case DECIMAL -> "decimal";
            case DATE -> "date";
            case INSTANT -> "instant";
        };
        String kind = switch (attribute.valueType()) {
            case BOOLEAN -> Library.ATTRIBUTE_REF;
            case INT, LONG, DECIMAL -> Library.NUMERIC_ATTRIBUTE_REF;
            case STRING, DATE, INSTANT -> Library.ORDERED_ATTRIBUTE_REF;
        };
        out.line(ref(kind) + "<" + boxed(attribute.valueType()) + "> " + name + " = " + ref(Library.ATTRIBUTE_REF) + "."
                + factory + "(" + Docs.literal(attribute.name()) + ");");
    }

    private void writeAccessor(Attribute attribute) {
        member();
        List<String> tags = new ArrayList<>();
        tags.add("@return the value of " + Docs.code(attribute.name())
                + (attribute.required() ? "" : ", or empty if the " + thing() + " has none"));
        if (attribute.deprecated()) {
            tags.add("@deprecated The attribute " + Docs.code(attribute.name())
                    + " is deprecated in its schema document.");
        }
        out.javadoc(doc(summary(attribute.description(),
                "The value of the attribute " + Docs.code(attribute.name()) + "."),
                Docs.constraints(attribute, things()), tags));
        if (attribute.deprecated()) {
            out.line("@" + ref(Library.DEPRECATED));
        }
        out.line(javaType(attribute) + " " + attribute(attribute) + "();");
    }

    private void writeFrom() {
        member();
        String self = type(element);
        out.javadoc(doc("Reads a state as " + Docs.code(element.name()) + ": as the snapshot record of the state's own type "
                        + "if that is " + Docs.code(element.name()) + " or a generated subtype of it, and as the "
                        + "snapshot record of " + Docs.code(element.name()) + " if the state's type is one that the "
                        + "generated code does not know, such as a subtype that was registered later.",
                "@param state the state of " + (relationship ? "a relationship" : "an entity"),
                "@return the " + thing() + " as " + a(element.name()),
                "@throws " + ref(Library.ILLEGAL_ARGUMENT_EXCEPTION) + " if the state's type is a generated type that "
                        + "is neither " + Docs.code(element.name()) + " nor one of its subtypes, or the state lacks "
                        + "a required value"));
        out.open("static " + self + " from(" + state() + " state) {");
        String typeOf = relationship ? "state.relationshipType()" : "state.entityType()";
        out.open("return switch (" + typeOf + ") {");
        out.line("case " + Docs.literal(element.name()) + " -> " + nested(element, "Snapshot") + ".of(state);");
        List<String> subtypes = element.subtypes().stream().sorted().toList();
        for (String subtype : subtypes) {
            Element sub = model.element(subtype);
            out.line("case " + Docs.literal(subtype) + " -> " + type(sub) + "." + nested(sub, "Snapshot")
                    + ".of(state);");
        }
        List<String> others = model.elements().stream().filter(other -> other.kind() == element.kind())
                .map(Element::name).filter(name -> !name.equals(element.name()) && !subtypes.contains(name))
                .sorted().toList();
        if (!others.isEmpty()) {
            writeLabels(others);
            out.indent().indent();
            out.line("throw new " + ref(Library.ILLEGAL_ARGUMENT_EXCEPTION) + "(\"The " + thing() + " \" + state.id()"
                    + " + \" has the type \"");
            out.line("        + " + typeOf + " + "
                    + Docs.literal(", which is neither " + element.name() + " nor one of its subtypes.") + ");");
            out.outdent().outdent();
        }
        out.line("default -> " + nested(element, "Snapshot") + ".of(state);");
        out.close("};");
        out.close("}");
    }

    /**
     * Writes a static {@code by<Attribute>} method for each unique attribute of an entity type, inherited ones included,
     * which converts the Java value as the builders do and returns the {@code UniqueKey} of the type, the attribute and the
     * value (ADR-0026). Relationship types have none, because keys name entities.
     */
    private void writeKeys() {
        if (relationship) {
            return;
        }
        Set<String> taken = new HashSet<>();
        element.attributes().forEach(attribute -> taken.add(attribute(attribute)));
        for (Attribute attribute : element.attributes()) {
            if (!attribute.unique()) {
                continue;
            }
            String method = model.names().key(attribute, taken);
            member();
            List<String> tags = new ArrayList<>();
            tags.add("@param value the value of " + Docs.code(attribute.name()));
            tags.add("@return the unique key of " + a(element.name()) + " that has the value");
            if (attribute.deprecated()) {
                tags.add("@deprecated The attribute " + Docs.code(attribute.name())
                        + " is deprecated in its schema document.");
            }
            out.javadoc(doc("Names the entity of " + Docs.code(element.name()) + ", or of a subtype, that has a value of "
                    + "the unique attribute " + Docs.code(attribute.name()) + ": a reference for a write, which the "
                    + "store resolves when it commits, and a key for a view to find the entity by.", List.of(), tags));
            if (attribute.deprecated()) {
                out.line("@" + ref(Library.DEPRECATED));
            }
            out.open("static " + ref(Library.UNIQUE_KEY) + " " + method + "(" + scalar(attribute.valueType())
                    + " value) {");
            out.line("return new " + ref(Library.UNIQUE_KEY) + "(TYPE, " + Docs.literal(attribute.name()) + ", "
                    + ref(Library.VALUE) + ".of(value));");
            out.close("}");
        }
    }

    /**
     * Writes {@code from(state, schema)}, which finds the nearest type that the generated code knows among the state's
     * type and its supertypes in the schema, and reads the state as that type, as {@code from(state)} does for a type
     * that it knows.
     */
    private void writeFromInSchema() {
        member();
        String self = type(element);
        String name = Docs.code(element.name());
        out.javadoc(doc("Reads a state as " + name + ", with the schema that it was read in: as the snapshot record of "
                        + "the nearest of the state's type and its supertypes in the schema that the generated code "
                        + "knows, if that is " + name + " or a generated subtype of it. A state of a type that was "
                        + "registered after generation, such as a subtype of a generated type, so reads as its nearest "
                        + "generated supertype. If no type of that line is one that the generated code knows, the state "
                        + "reads as the snapshot record of " + name + ".",
                "@param state the state of " + (relationship ? "a relationship" : "an entity"),
                "@param schema the schema to look the state's type and its supertypes up in, such as the schema of the "
                        + "view that the state was read from",
                "@return the " + thing() + " as " + a(element.name()),
                "@throws " + ref(Library.ILLEGAL_ARGUMENT_EXCEPTION) + " if the nearest generated type of the state is "
                        + "neither " + name + " nor one of its subtypes, or the state lacks a required value"));
        out.open("static " + self + " from(" + state() + " state, " + ref(Library.SCHEMA_TYPE) + " schema) {");
        String line = relationship ? "relationshipTypeAndSupertypes" : "entityTypeAndSupertypes";
        String typeOf = relationship ? "state.relationshipType()" : "state.entityType()";
        out.open("for (" + ref(Library.STRING) + " candidate : " + ref(Library.TYPED_VALUES) + "." + line + "(schema, " + typeOf + ")) {");
        out.open("switch (candidate) {");
        writeCase(element.name(), nested(element, "Snapshot"));
        List<String> subtypes = element.subtypes().stream().sorted().toList();
        for (String subtype : subtypes) {
            Element sub = model.element(subtype);
            writeCase(subtype, type(sub) + "." + nested(sub, "Snapshot"));
        }
        List<String> others = model.elements().stream().filter(other -> other.kind() == element.kind())
                .map(Element::name).filter(candidate -> !candidate.equals(element.name())
                        && !subtypes.contains(candidate))
                .sorted().toList();
        if (!others.isEmpty()) {
            writeLabels(others);
            out.indent().indent();
            out.line("throw new " + ref(Library.ILLEGAL_ARGUMENT_EXCEPTION) + "(\"The " + thing() + " \" + state.id()"
                    + " + \" has the type \"");
            out.line("        + " + typeOf + " + \", whose nearest generated type is \" + candidate");
            out.line("        + " + Docs.literal(", which is neither " + element.name() + " nor one of its subtypes.")
                    + ");");
            out.outdent().outdent();
        }
        out.line("default -> { }");
        out.close("}");
        out.close("}");
        out.line("return " + nested(element, "Snapshot") + ".of(state);");
        out.close("}");
    }

    /** Writes a case of the switch of {@code from(state, schema)} that returns the snapshot of a state of a type. */
    private void writeCase(String typeName, String snapshot) {
        out.open("case " + Docs.literal(typeName) + " -> {");
        out.line("return " + snapshot + ".of(state);");
        out.close("}");
    }

    /** Writes {@code case "A", "B" ->}, over several lines if it is long. */
    private void writeLabels(List<String> names) {
        StringBuilder line = new StringBuilder("case ");
        for (int i = 0; i < names.size(); i++) {
            String label = Docs.literal(names.get(i)) + (i < names.size() - 1 ? "," : " ->");
            if (line.length() > 5 && line.length() + 1 + label.length() > 80) {
                out.line(line.toString());
                line = new StringBuilder("        ");
            } else if (line.length() > 5 && line.charAt(line.length() - 1) != ' ') {
                line.append(' ');
            }
            line.append(label);
        }
        out.line(line.toString());
    }

    private void writeFactories() {
        if (relationship) {
            writeRelate();
        } else if (!element.isAbstract()) {
            member();
            out.javadoc(doc("Starts a write that creates " + a(element.name()) + ".", "@return the builder"));
            out.open("static " + nested(element, "Create") + " create() {");
            out.line("return new " + nested(element, "Create") + "();");
            out.close("}");
        }
        writeTargetFactory("patch", "Patch", "patches");
        writeTargetFactory("replace", "Replace", "replaces");
    }

    private void writeTargetFactory(String method, String builder, String verb) {
        member();
        out.javadoc(doc("Starts a write that " + verb + " " + a(element.name()) + ".",
                "@param target the " + thing() + ", by its ID"
                        + (relationship ? "" : ", by a unique key, such as one that a <code>by</code> method makes,")
                        + " or by the temporary ID of one that the transaction creates earlier",
                "@return the builder"));
        out.open("static " + nested(element, builder) + " " + method + "(" + ref(Library.REF) + " target) {");
        out.line("return new " + nested(element, builder) + "(target);");
        out.close("}");
    }

    private void writeRelate() {
        Element sourceType = model.element(element.source());
        Element targetType = model.element(element.target());
        member();
        out.javadoc(doc("Starts a write that relates two entities with " + a(element.name()) + ".",
                "@param source the source, by its ID, by a unique key or by the temporary ID of an entity that the "
                        + "transaction creates earlier",
                "@param target the target, by its ID, by a unique key or by the temporary ID of an entity that the "
                        + "transaction creates earlier",
                "@return the builder"));
        out.open("static " + nested(element, "Relate") + " relate(" + ref(Library.REF) + " source, "
                + ref(Library.REF) + " target) {");
        out.line("return new " + nested(element, "Relate") + "(source, target);");
        out.close("}");
        // Described in the same words, for the generated types of the two ends.
        member();
        out.javadoc(doc("Starts a write that relates two entities with " + a(element.name()) + ".",
                "@param source the source, " + a(sourceType.name()) + " that exists",
                "@param target the target, " + a(targetType.name()) + " that exists",
                "@return the builder"));
        out.open("static " + nested(element, "Relate") + " relate(" + type(sourceType) + " source, "
                + type(targetType) + " target) {");
        out.line("return relate(source.id(), target.id());");
        out.close("}");
    }

    /** {@code a Person}, {@code an Organization}: the article for a name, in code font. */
    private static String a(String name) {
        return ("AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + Docs.code(name);
    }

    // ------------------------------------------------------------------ the snapshot record

    private void writeSnapshot() {
        String snapshot = nested(element, "Snapshot");
        member();
        List<String> lines = new ArrayList<>();
        lines.add("A snapshot record of " + a(element.name()) + ": the " + (relationship ? "relationship's" : "entity's")
                + " generic state and the value of every attribute, read when the record was made.");
        lines.add("");
        lines.add("@param " + stateMethod() + " the generic state that this record was read from");
        for (Attribute attribute : element.attributes()) {
            lines.add("@param " + attribute(attribute) + " the value of " + Docs.code(attribute.name())
                    + (attribute.derived().isPresent() ? ", which is derived from " + Docs.code(attribute.derived().get())
                            : ""));
        }
        out.javadoc(lines);
        List<String> components = new ArrayList<>();
        components.add(state() + " " + stateMethod());
        element.attributes().forEach(attribute -> components.add(javaType(attribute) + " " + attribute(attribute)));
        String head = "record " + snapshot + "(" + String.join(", ", components) + ") implements " + element.java() + " {";
        if (head.length() <= 100) {
            out.open(head);
        } else {
            out.line("record " + snapshot + "(");
            for (int i = 0; i < components.size(); i++) {
                out.line("        " + components.get(i) + (i < components.size() - 1 ? "," : ") implements "
                        + element.java() + " {"));
            }
            out.indent();
        }
        out.open("static " + snapshot + " of(" + state() + " state) {");
        if (element.attributes().isEmpty()) {
            out.line("return new " + snapshot + "(state);");
        } else {
            out.line(ref(Library.MAP) + "<" + ref(Library.STRING) + ", " + ref(Library.VALUE)
                    + "> values = state.values();");
            out.line("return new " + snapshot + "(");
            out.indent().indent();
            out.line("state,");
            List<Attribute> attributes = element.attributes();
            for (int i = 0; i < attributes.size(); i++) {
                Attribute attribute = attributes.get(i);
                out.line(ref(Library.TYPED_VALUES) + "." + reader(attribute) + "(values, "
                        + Docs.literal(attribute.name()) + ")" + (i < attributes.size() - 1 ? "," : ");"));
            }
            out.outdent().outdent();
        }
        out.close("}");
        out.close("}");
    }

    // ------------------------------------------------------------------ builders

    /**
     * The attributes that the builders of writes have setters for: all but the derived ones, which the store computes and
     * a write cannot set (design D7 of {@code add-derived-attributes}).
     */
    private List<Attribute> settable() {
        return element.attributes().stream().filter(attribute -> attribute.derived().isEmpty()).toList();
    }

    /** What the three kinds of builder have in common: the values they set and how they set them. */
    private void setter(String builder, Attribute attribute, boolean patch) {
        out.blank();
        List<String> tags = new ArrayList<>();
        tags.add("@param value the value");
        tags.add("@return this builder");
        if (attribute.deprecated()) {
            tags.add("@deprecated The attribute " + Docs.code(attribute.name())
                    + " is deprecated in its schema document.");
        }
        out.javadoc(doc("Sets the value of the attribute " + Docs.code(attribute.name()) + ".", List.of(), tags));
        if (attribute.deprecated()) {
            out.line("@" + ref(Library.DEPRECATED));
        }
        out.open("public " + builder + " " + attribute(attribute) + "(" + scalar(attribute.valueType()) + " value) {");
        out.line((patch ? "set" : "values") + ".put(" + Docs.literal(attribute.name()) + ", " + ref(Library.VALUE)
                + ".of(value));");
        if (patch) {
            out.line("clear.remove(" + Docs.literal(attribute.name()) + ");");
        }
        out.line("return this;");
        out.close("}");
    }

    private void clearer(String builder, Attribute attribute) {
        out.blank();
        List<String> tags = new ArrayList<>();
        tags.add("@return this builder");
        if (attribute.deprecated()) {
            tags.add("@deprecated The attribute " + Docs.code(attribute.name())
                    + " is deprecated in its schema document.");
        }
        out.javadoc(doc("Clears the attribute " + Docs.code(attribute.name()) + ": it has no value in the period.",
                List.of(), tags));
        if (attribute.deprecated()) {
            out.line("@" + ref(Library.DEPRECATED));
        }
        out.open("public " + builder + " clear" + capitalize(attribute.name()) + "() {");
        out.line("clear.add(" + Docs.literal(attribute.name()) + ");");
        out.line("set.remove(" + Docs.literal(attribute.name()) + ");");
        out.line("return this;");
        out.close("}");
    }

    private void checker(String builder) {
        out.blank();
        out.javadoc(doc("Asks for a conflict check against the state that the caller read; without one, the write "
                        + "wins over any change made since.",
                "@param check the conflict check",
                "@return this builder"));
        out.open("public " + builder + " check(" + ref(Library.CONFLICT_CHECK) + " check) {");
        out.line("this.check = check;");
        out.line("return this;");
        out.close("}");
    }

    /** The terminal methods: the write, for a period or from an instant. */
    private void terminals(String write, String creation) {
        out.blank();
        out.javadoc(doc("Builds the write for an effective period.",
                "@param period when the " + thing() + " is effective",
                "@return the write"));
        out.open("public " + write + " effective(" + ref(Library.EFFECTIVE_PERIOD) + " period) {");
        out.line("return " + creation + ";");
        out.close("}");
        out.blank();
        out.javadoc(doc("Builds the write for an effective period without an end.",
                "@param from the first instant of the period",
                "@return the write"));
        out.open("public " + write + " effectiveFrom(" + ref(Library.INSTANT) + " from) {");
        out.line("return effective(" + ref(Library.EFFECTIVE_PERIOD) + ".from(from));");
        out.close("}");
    }

    private void writeCreateBuilder() {
        String builder = nested(element, "Create");
        String optionalTemporary = ref(Library.OPTIONAL) + "<" + ref(Library.TEMPORARY_ID) + ">";
        String optionalId = ref(Library.OPTIONAL) + "<" + ref(Library.ENTITY_ID) + ">";
        member();
        out.javadoc(List.of("Builds a write that creates " + a(element.name()) + "."));
        out.open("final class " + builder + " {");
        out.line("private " + optionalTemporary + " temporaryId = " + ref(Library.OPTIONAL) + ".empty();");
        out.line("private " + optionalId + " id = " + ref(Library.OPTIONAL) + ".empty();");
        out.line("private final " + values() + " values = new " + ref(Library.LINKED_HASH_MAP) + "<>();");
        out.blank();
        out.open("private " + builder + "() {");
        out.close("}");
        out.blank();
        out.javadoc(doc("Names the entity, so that other writes of the transaction can refer to it.",
                "@param temporaryId the name, not blank",
                "@return this builder"));
        out.open("public " + builder + " as(" + ref(Library.STRING) + " temporaryId) {");
        out.line("this.temporaryId = " + ref(Library.OPTIONAL) + ".of(new " + ref(Library.TEMPORARY_ID)
                + "(temporaryId));");
        out.line("return this;");
        out.close("}");
        out.blank();
        out.javadoc(doc("Gives the entity an ID, in the format of the store's ID generator, instead of a generated one.",
                "@param id the ID",
                "@return this builder"));
        out.open("public " + builder + " id(" + ref(Library.ENTITY_ID) + " id) {");
        out.line("this.id = " + ref(Library.OPTIONAL) + ".of(id);");
        out.line("return this;");
        out.close("}");
        settable().forEach(attribute -> setter(builder, attribute, false));
        terminals(ref(Library.WRITE) + ".Create",
                "new " + ref(Library.WRITE) + ".Create(temporaryId, id, TYPE, period, values)");
        out.close("}");
    }

    private void writeRelateBuilder() {
        String builder = nested(element, "Relate");
        String optionalTemporary = ref(Library.OPTIONAL) + "<" + ref(Library.TEMPORARY_ID) + ">";
        String optionalId = ref(Library.OPTIONAL) + "<" + ref(Library.RELATIONSHIP_ID) + ">";
        String refType = ref(Library.REF);
        member();
        out.javadoc(List.of("Builds a write that relates two entities with " + a(element.name()) + "."));
        out.open("final class " + builder + " {");
        out.line("private " + optionalTemporary + " temporaryId = " + ref(Library.OPTIONAL) + ".empty();");
        out.line("private " + optionalId + " id = " + ref(Library.OPTIONAL) + ".empty();");
        out.line("private final " + refType + " source;");
        out.line("private final " + refType + " target;");
        out.line("private final " + values() + " values = new " + ref(Library.LINKED_HASH_MAP) + "<>();");
        out.blank();
        out.open("private " + builder + "(" + refType + " source, " + refType + " target) {");
        out.line("this.source = source;");
        out.line("this.target = target;");
        out.close("}");
        out.blank();
        out.javadoc(doc("Names the relationship, so that other writes of the transaction can refer to it.",
                "@param temporaryId the name, not blank",
                "@return this builder"));
        out.open("public " + builder + " as(" + ref(Library.STRING) + " temporaryId) {");
        out.line("this.temporaryId = " + ref(Library.OPTIONAL) + ".of(new " + ref(Library.TEMPORARY_ID)
                + "(temporaryId));");
        out.line("return this;");
        out.close("}");
        out.blank();
        out.javadoc(doc("Gives the relationship an ID, in the format of the store's ID generator, instead of a "
                        + "generated one.",
                "@param id the ID",
                "@return this builder"));
        out.open("public " + builder + " id(" + ref(Library.RELATIONSHIP_ID) + " id) {");
        out.line("this.id = " + ref(Library.OPTIONAL) + ".of(id);");
        out.line("return this;");
        out.close("}");
        settable().forEach(attribute -> setter(builder, attribute, false));
        terminals(ref(Library.WRITE) + ".Relate",
                "new " + ref(Library.WRITE) + ".Relate(temporaryId, id, TYPE, source, target, period, values)");
        out.close("}");
    }

    private String values() {
        return ref(Library.MAP) + "<" + ref(Library.STRING) + ", " + ref(Library.VALUE) + ">";
    }

    private void writePatch() {
        String builder = nested(element, "Patch");
        String refType = ref(Library.REF);
        member();
        out.javadoc(List.of("Builds a write that sets some attributes of " + a(element.name())
                + " for a period and leaves the others as they are."));
        out.open("final class " + builder + " {");
        out.line("private final " + refType + " target;");
        out.line("private final " + values() + " set = new " + ref(Library.LINKED_HASH_MAP) + "<>();");
        out.line("private final " + ref(Library.SET) + "<" + ref(Library.STRING) + "> clear = new "
                + ref(Library.LINKED_HASH_SET) + "<>();");
        out.line("private " + ref(Library.CONFLICT_CHECK) + " check = " + ref(Library.CONFLICT_CHECK) + ".none();");
        out.blank();
        out.open("private " + builder + "(" + refType + " target) {");
        out.line("this.target = target;");
        out.close("}");
        for (Attribute attribute : settable()) {
            setter(builder, attribute, true);
            if (!attribute.required()) {
                clearer(builder, attribute);
            }
        }
        checker(builder);
        terminals(ref(Library.WRITE) + ".Patch",
                "new " + ref(Library.WRITE) + ".Patch(target, period, set, clear, check)");
        out.close("}");
    }

    private void writeReplace() {
        String builder = nested(element, "Replace");
        String refType = ref(Library.REF);
        member();
        out.javadoc(List.of("Builds a write that sets the complete state of " + a(element.name())
                + " for a period: attributes that the write does not set have no value in the period."));
        out.open("final class " + builder + " {");
        out.line("private final " + refType + " target;");
        out.line("private final " + values() + " values = new " + ref(Library.LINKED_HASH_MAP) + "<>();");
        out.line("private " + ref(Library.CONFLICT_CHECK) + " check = " + ref(Library.CONFLICT_CHECK) + ".none();");
        out.blank();
        out.open("private " + builder + "(" + refType + " target) {");
        out.line("this.target = target;");
        out.close("}");
        settable().forEach(attribute -> setter(builder, attribute, false));
        checker(builder);
        terminals(ref(Library.WRITE) + ".Replace",
                "new " + ref(Library.WRITE) + ".Replace(target, period, values, check)");
        out.close("}");
    }
}
