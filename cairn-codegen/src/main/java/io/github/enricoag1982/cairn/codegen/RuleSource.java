package io.github.enricoag1982.cairn.codegen;

import java.util.List;

/**
 * Writes the base class of a rule that the documents name (design D3 of {@code add-java-type-generation}): it
 * implements the rule interface for the kind of type the rule is for, holds the rule's name, version and type, and
 * passes each version to a typed check that subclasses write.
 */
final class RuleSource {

    private final Model model;
    private final Model.RuleBase rule;
    private final Imports imports;
    private final Source out = new Source();

    private RuleSource(Model model, Model.RuleBase rule) {
        this.model = model;
        this.rule = rule;
        this.imports = new Imports(model.types());
    }

    static String of(Model model, Model.RuleBase rule) {
        RuleSource source = new RuleSource(model, rule);
        source.write();
        return SourceFile.assemble(model, source.imports, source.out);
    }

    private String ref(String library) {
        return imports.ref(library);
    }

    private void write() {
        boolean relationship = rule.appliesTo().kind() == Element.Kind.RELATIONSHIP;
        String name = rule.rule().name();
        String version = Integer.toString(rule.rule().version());
        String appliesTo = rule.appliesTo().name();
        String thing = relationship ? "relationship" : "entity";
        String article = (relationship ? "a " : "an ") + thing;
        String subject = rule.appliesTo().java();
        String state = ref(relationship ? Library.RELATIONSHIP_STATE : Library.ENTITY_STATE);
        String context = ref(Library.RULE_CONTEXT);

        out.javadoc(List.of(
                "The base class of the rule " + Docs.code(name) + " in version " + version + ", which is for "
                        + Docs.code(appliesTo) + " and its subtypes.",
                "",
                "<p>A subclass gives the rule's description and problem codes with {@link #describe}, returns the "
                        + "descriptor, and writes the typed check. This class reads each version of " + article
                        + " as " + a(appliesTo) + " and passes it to that check, and reads the " + thing
                        + " as the store held it before the transaction with {@link #previous}."));
        if (model.deprecations()) {
            out.line("@" + ref(Library.SUPPRESS_WARNINGS) + "(\"deprecation\")");
        }
        out.open("public abstract class " + rule.java() + " implements "
                + ref(relationship ? Library.RELATIONSHIP_RULE : Library.ENTITY_RULE) + " {");
        out.blank();

        out.javadoc(List.of("The name of the rule."));
        out.line("public static final " + ref(Library.STRING) + " NAME = " + Docs.literal(name) + ";");
        out.blank();
        out.javadoc(List.of("The version of the rule."));
        out.line("public static final int VERSION = " + version + ";");
        out.blank();
        out.javadoc(List.of("The name of the type the rule is for."));
        out.line("public static final " + ref(Library.STRING) + " APPLIES_TO = " + Docs.literal(appliesTo) + ";");
        out.blank();

        out.javadoc(List.of("Creates a rule."));
        out.open("protected " + rule.java() + "() {");
        out.close("}");
        out.blank();

        out.javadoc(List.of(
                "Describes the rule with its name, version and type, which this class holds, and the description and "
                        + "problem codes that a subclass gives.",
                "",
                "@param description what the rule checks",
                "@param codes       the problem codes the rule can report",
                "@return the descriptor"));
        out.open("protected static " + ref(Library.RULE_DESCRIPTOR) + " describe(" + ref(Library.STRING)
                + " description, " + ref(Library.RULE_CODE) + "... codes) {");
        out.line("return new " + ref(Library.RULE_DESCRIPTOR) + "(NAME, VERSION, description, APPLIES_TO, "
                + ref(Library.LIST) + ".of(codes));");
        out.close("}");
        out.blank();

        out.line("@" + ref(Library.OVERRIDE));
        out.open("public final void check(" + state + " version, " + context + " context) {");
        out.line("check(" + subject + ".from(version), context);");
        out.close("}");
        out.blank();

        String list = ref(Library.LIST);
        String period = ref(Library.EFFECTIVE_PERIOD);
        String stateOfVersion = relationship ? "relationshipState" : "entityState";
        String timeline = relationship ? "relationshipTimeline" : "timeline";
        out.javadoc(List.of(
                "Reads the versions of the " + thing + " that a version belongs to as the store held them before the "
                        + "transaction, during the period of the version that is checked, as " + a(appliesTo)
                        + " each, in the order of the timeline. A rule compares with each of them, so that its verdict "
                        + "holds at every instant of the period and does not depend on where the period starts. It "
                        + "reads the timeline through the {@link " + context + "#before() before} view of the "
                        + "context, so that the store records it as a read of the " + thing + " across effective time.",
                "",
                "@param version the version that is checked, which names the " + thing + " to read",
                "@param context what the rule reads and reports through",
                "@return the versions that the store held with the version's ID whose periods overlap the period of "
                        + "the version, in order; empty if it held none there, for example because the transaction "
                        + "creates it"));
        out.open("protected " + list + "<" + subject + "> previous(" + subject + " version, " + context
                + " context) {");
        out.line(period + " period = version." + stateOfVersion + "().period();");
        out.line("return context.before()." + timeline + "(version.id()).stream()");
        out.line("        .filter(held -> period.until().map(end -> held.period().from().isBefore(end)).orElse(true)");
        out.line("                && held.period().until().map(end -> period.from().isBefore(end)).orElse(true))");
        out.line("        .map(" + subject + "::from)");
        out.line("        .toList();");
        out.close("}");
        out.blank();

        out.javadoc(List.of(
                "Checks one version of " + article + ", read as " + a(appliesTo) + ". It reports what is wrong "
                        + "through the context, as the rule interface says.",
                "",
                "@param value   the version, read as " + a(appliesTo),
                "@param context what the rule reads and reports through"));
        out.line("protected abstract void check(" + subject + " value, " + context + " context);");
        out.close("}");
    }

    private static String a(String name) {
        return ("AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + Docs.code(name);
    }
}
