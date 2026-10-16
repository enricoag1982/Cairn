package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import io.github.enricoag1982.cairn.data.Clearance;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The options of {@link CairnAgentServer}: {@code --schema <file>} (repeatable), {@code --principal <name>},
 * {@code --on-behalf-of <name>}, {@code --policy schema=<allow|approve|deny>,data=<allow|approve|deny>} (design D7 of
 * {@code add-agent-tools}) and {@code --clearance <classification>[,<classification>...]} (design D5 of
 * {@code add-attribute-access-control}).
 *
 * @param schemas    the schema documents to register at start, in the order given
 * @param principal  the principal the server acts as
 * @param onBehalfOf the party the principal acts for, if any
 * @param policy     the approval policy
 * @param clearance  the classifications the agent may read and write; none unless the option names some
 * @param help       whether the options ask for the usage text instead of a server
 */
record ServerOptions(List<Path> schemas, String principal, Optional<String> onBehalfOf, AgentPolicy policy,
        Clearance clearance, boolean help) {

    /** The form of a classification, as the schema spells it: lowercase letters, digits and hyphens, from a letter. */
    private static final Pattern CLASSIFICATION = Pattern.compile("[a-z][a-z0-9-]{0,127}");

    /** The usage text. */
    static final String USAGE = """
            Usage: cairn-agent-tools --principal <name> [--schema <file>]... [--on-behalf-of <name>] \
            [--policy schema=<allow|approve|deny>,data=<allow|approve|deny>] [--clearance <classification>[,...]]

            Serves an in-memory Cairn store to AI agents as tools of the Model Context Protocol over standard input \
            and output, until the input closes. Nothing else is written to standard output.

              --schema <file>      a schema document (YAML or JSON) to register at start; repeat for several
              --principal <name>   who the agent acts as; recorded on every transaction (required)
              --on-behalf-of <name>  the party the principal acts for
              --policy <policy>    what happens to registrations (schema) and commits (data): allow (default), \
            approve (a person must approve it) or deny
              --clearance <list>   the classifications of attributes that the agent may read and write, such as pay; \
            by default none, and the values of classified attributes are withheld
              --help               print this text
            """;

    /** What is wrong with the options. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /**
     * Reads the options.
     *
     * @throws Invalid if an option is unknown, lacks its value or has a wrong one, or the principal is missing
     */
    static ServerOptions parse(String[] args) throws Invalid {
        List<Path> schemas = new ArrayList<>();
        String principal = null;
        String onBehalfOf = null;
        Decision schema = Decision.ALLOW;
        Decision data = Decision.ALLOW;
        boolean policyGiven = false;
        Clearance clearance = null;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String inlineValue = null;
            int equals = name.startsWith("--") ? name.indexOf('=') : -1;
            if (equals > 0) {
                inlineValue = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (name.equals("--help") || name.equals("-h")) {
                return new ServerOptions(List.of(), "", Optional.empty(), AgentPolicy.defaults(), Clearance.none(), true);
            }
            switch (name) {
                case "--schema", "--principal", "--on-behalf-of", "--policy", "--clearance" -> {
                    String value = inlineValue;
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new Invalid("The option " + name + " needs a value.");
                        }
                        value = args[++i];
                    }
                    switch (name) {
                        case "--schema" -> schemas.add(Path.of(value));
                        case "--principal" -> principal = value;
                        case "--on-behalf-of" -> onBehalfOf = value;
                        case "--clearance" -> {
                            if (clearance != null) {
                                throw new Invalid("The option --clearance is given twice.");
                            }
                            clearance = clearance(value);
                        }
                        default -> {
                            if (policyGiven) {
                                throw new Invalid("The option --policy is given twice.");
                            }
                            policyGiven = true;
                            Decision[] decisions = policy(value);
                            schema = decisions[0];
                            data = decisions[1];
                        }
                    }
                }
                default -> throw new Invalid("Unknown option " + args[i] + ".");
            }
        }
        if (principal == null || principal.isBlank()) {
            throw new Invalid("The option --principal is required: name who the agent acts as, for example "
                    + "agent:demo.");
        }
        if (onBehalfOf != null && onBehalfOf.isBlank()) {
            throw new Invalid("The option --on-behalf-of must not be blank.");
        }
        return new ServerOptions(List.copyOf(schemas), principal, Optional.ofNullable(onBehalfOf),
                new AgentPolicy(schema, data), clearance == null ? Clearance.none() : clearance, false);
    }

    /**
     * Reads a comma-separated list of classifications. An empty value is the clearance that covers none; a list with an
     * empty element, or a name that is not written like a classification, is wrong.
     */
    private static Clearance clearance(String text) throws Invalid {
        if (text.isEmpty()) {
            return Clearance.none();
        }
        List<String> classifications = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String name = part.strip();
            if (!CLASSIFICATION.matcher(name).matches()) {
                throw new Invalid("The classification '" + name + "' in the clearance must be lowercase letters, "
                        + "digits and hyphens, starting with a letter, like pay or identity-data.");
            }
            classifications.add(name);
        }
        return Clearance.of(classifications.toArray(String[]::new));
    }

    /** Reads {@code schema=<decision>,data=<decision>}: the schema decision and then the data decision. */
    private static Decision[] policy(String text) throws Invalid {
        Decision schema = Decision.ALLOW;
        Decision data = Decision.ALLOW;
        boolean schemaGiven = false;
        boolean dataGiven = false;
        for (String part : text.split(",", -1)) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new Invalid("The policy '" + text + "' must be like schema=allow,data=approve.");
            }
            String key = part.substring(0, equals).strip();
            Decision decision = switch (part.substring(equals + 1).strip()) {
                case "allow" -> Decision.ALLOW;
                case "approve" -> Decision.REQUIRE_APPROVAL;
                case "deny" -> Decision.DENY;
                default -> throw new Invalid("The decision '" + part.substring(equals + 1).strip() + "' in the policy "
                        + "must be allow, approve or deny.");
            };
            switch (key) {
                case "schema" -> {
                    if (schemaGiven) {
                        throw new Invalid("The policy gives schema twice.");
                    }
                    schemaGiven = true;
                    schema = decision;
                }
                case "data" -> {
                    if (dataGiven) {
                        throw new Invalid("The policy gives data twice.");
                    }
                    dataGiven = true;
                    data = decision;
                }
                default -> throw new Invalid("The policy names '" + key + "', but only schema and data.");
            }
        }
        return new Decision[] {schema, data};
    }
}
