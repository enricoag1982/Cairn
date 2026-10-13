package io.github.enricoag1982.cairn.agents;

import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.Rule;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.documents.DocumentReadResult;
import io.github.enricoag1982.cairn.documents.SchemaDocument;
import io.github.enricoag1982.cairn.documents.SchemaDocuments;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import io.github.enricoag1982.cairn.schema.RuleReference;
import io.github.enricoag1982.cairn.schema.Submission;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * A server that serves an in-memory store to AI agents over standard input and output (ADR-0017, design D7 of
 * {@code add-agent-tools}, capability {@code agent-tools}). Start it from an MCP client such as Claude Code:
 *
 * <pre>
 * java -cp ... io.github.enricoag1982.cairn.agents.CairnAgentServer \
 *     --principal agent:demo --schema docs/reference/reference-model.cairn.yaml
 * </pre>
 *
 * <p>The options are {@code --schema <file>}, repeatable, a schema document to register at start;
 * {@code --principal <name>}, required, who the agent acts as; {@code --on-behalf-of <name>}; and
 * {@code --policy schema=<allow|approve|deny>,data=<allow|approve|deny>}, which defaults to allowing both. The server
 * runs until its input closes. Standard output carries the protocol and nothing else; messages for the person who
 * started the server go to standard error.
 *
 * <p>A client must keep the input open until it has the responses it waits for: the SDK's transport for standard
 * input and output stops writing when the input closes, so a request that is still being answered then gets no
 * response.
 *
 * <p>The store lives in memory and is gone when the server stops. The rules that run are those that Java's
 * {@link java.util.ServiceLoader} finds on the server's class path ({@code Rule} providers, ADR-0018); add a jar with
 * rules to the class path to have the server run them. A rule that a schema document names and that is not found is
 * declared without code, from what the document says: its name and version, the first type that names it, and the
 * file. The document can then be registered, but every write of an entity or relationship whose type names the rule
 * is rejected with {@code RULE_UNAVAILABLE}. The server is for trusted, local use: there is no authentication, and an
 * agent can read everything the store holds.
 */
public final class CairnAgentServer {

    private CairnAgentServer() {
    }

    /**
     * Starts the server on standard input and output, and exits when the input closes. Exits with status 2 if the
     * options are wrong, and 1 if a schema document cannot be read or registered.
     *
     * @param args the options
     */
    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err);
        System.exit(status);
    }

    /**
     * Runs the server on the given streams until the input closes.
     *
     * @param args   the options
     * @param in     where the protocol messages come from
     * @param out    where the protocol messages go; nothing else is written to it
     * @param err    where messages for the person who started the server go
     * @return the exit status: 0 after the input closed, 1 if a schema document cannot be read or registered, 2 if the
     *         options are wrong
     */
    static int run(String[] args, InputStream in, OutputStream out, PrintStream err) {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (ServerOptions.Invalid e) {
            err.println(e.getMessage());
            err.print(ServerOptions.USAGE);
            return 2;
        }
        if (options.help()) {
            try {
                out.write(ServerOptions.USAGE.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                err.println("Cannot print the usage: " + e.getMessage());
                return 1;
            }
            return 0;
        }
        Store store;
        try {
            store = createStore(options);
        } catch (StartFailure e) {
            err.println(e.getMessage());
            return 1;
        }
        ApprovalQueue queue = new ApprovalQueue(store);
        AgentTools tools = new AgentTools(store, options.policy(), queue, options.principal(), options.onBehalfOf());
        EndOfInput input = new EndOfInput(in);
        McpSyncServer server = McpAdapter.serve(tools,
                new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, out));
        err.println("Cairn agent server ready: acting as " + options.principal() + ", serving "
                + store.schema().current().origins().size() + " origin(s) over standard input and output.");
        try {
            input.closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.closeGracefully();
        }
        return 0;
    }

    /**
     * Creates the store and registers the documents, as the principal of the server; the reason of each registration
     * says where it came from.
     *
     * @throws StartFailure if a document cannot be read or registered, or the rules on the class path cannot be used
     */
    static Store createStore(ServerOptions options) throws StartFailure {
        List<SchemaDocument> documents = new ArrayList<>();
        for (Path file : options.schemas()) {
            documents.add(read(file));
        }
        Store store = createStore(documents, options.schemas());
        for (int i = 0; i < documents.size(); i++) {
            SchemaDocument document = documents.get(i);
            Path file = options.schemas().get(i);
            Submission submission = new Submission(options.principal(), options.onBehalfOf(),
                    Optional.of("Registered at server start from " + file.getFileName()));
            if (store.schema().register(document.origin(), document.definition(), submission)
                    instanceof RegistrationResult.Rejected rejected) {
                throw new StartFailure("The schema document " + file + " cannot be registered:\n"
                        + problems(document.locate(rejected.problems())));
            }
        }
        return store;
    }

    /**
     * Creates the store with the rules that the class path provides, and declares without code the rules that the
     * documents name and the class path does not provide.
     */
    private static Store createStore(List<SchemaDocument> documents, List<Path> files) throws StartFailure {
        try {
            StoreSettings provided = StoreSettings.builder().discoverRules().build();
            Set<RuleReference> known = new HashSet<>();
            for (Rule rule : provided.rules()) {
                known.add(new RuleReference(rule.descriptor().name(), rule.descriptor().version()));
            }
            return Stores.inMemory(new StoreSettings(provided.clock(), provided.idGenerator(), provided.rules(),
                    DeclaredRules.namedBy(files, documents, known)));
        } catch (ServiceConfigurationError | IllegalArgumentException | NullPointerException e) {
            throw new StartFailure("The rules on the class path cannot be used: " + e.getMessage());
        }
    }

    private static SchemaDocument read(Path file) throws StartFailure {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StartFailure("The schema document " + file + " cannot be read: " + e);
        }
        return switch (SchemaDocuments.read(text)) {
            case DocumentReadResult.Read read -> read.document();
            case DocumentReadResult.Invalid invalid -> throw new StartFailure("The schema document " + file
                    + " has problems:\n" + problems(invalid.problems()));
        };
    }

    private static String problems(List<Problem> problems) {
        StringBuilder text = new StringBuilder();
        for (Problem problem : problems) {
            text.append("  ").append(problem);
            problem.location().ifPresent(location -> text.append(" [").append(location).append(']'));
            problem.hint().ifPresent(hint -> text.append(" Hint: ").append(hint));
            text.append('\n');
        }
        return text.toString();
    }

    /** The server cannot start. */
    static final class StartFailure extends Exception {
        private static final long serialVersionUID = 1L;

        StartFailure(String message) {
            super(message);
        }
    }

    /** An input stream that notes when the input ends, so that the server can stop. */
    private static final class EndOfInput extends FilterInputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        EndOfInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            return seen(super.read());
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return seen(super.read(buffer, offset, length));
        }

        private int seen(int count) {
            if (count < 0) {
                closed.countDown();
            }
            return count;
        }
    }
}
