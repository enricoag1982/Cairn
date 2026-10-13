package io.github.enricoag1982.cairn.agents;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * A pair of MCP transports that connect a client and a server in the same JVM: what one sends, the other receives. The
 * SDK has no such transport in its main jar. Every message is written to JSON text and read back, so the tests see the
 * messages as they would be on a wire.
 *
 * <p>Give {@link #server()} to {@link McpAdapter#serve} and {@link #client()} to {@code McpClient.sync}.
 */
final class InProcessTransport {

    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private final ServerEnd server = new ServerEnd();
    private final ClientEnd client = new ClientEnd();

    /** The transport for the server. */
    McpServerTransportProvider server() {
        return server;
    }

    /** The transport for the client. */
    McpClientTransport client() {
        return client;
    }

    private McpSchema.JSONRPCMessage onTheWire(McpSchema.JSONRPCMessage message) {
        try {
            return McpSchema.deserializeJsonRpcMessage(mapper,
                    mapper.writeValueAsString(message));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The server's end: one session, created when the server is built. */
    private final class ServerEnd implements McpServerTransportProvider {

        private volatile McpServerSession session;

        @Override
        public void setSessionFactory(McpServerSession.Factory factory) {
            session = factory.create(new SessionEnd());
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return session.sendNotification(method, params);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return session == null ? Mono.empty() : session.closeGracefully();
        }
    }

    /** What the server's session sends through: to the client. */
    private final class SessionEnd implements McpServerTransport {

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> client.receive(onTheWire(message)));
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return mapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    /** The client's end. */
    private final class ClientEnd implements McpClientTransport {

        private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            this.handler = handler;
            return Mono.empty();
        }

        void receive(McpSchema.JSONRPCMessage message) {
            Mono.just(message).transform(handler).subscribe();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.defer(() -> server.session.handle(onTheWire(message)));
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return mapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }
}
