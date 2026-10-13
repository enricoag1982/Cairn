package io.github.enricoag1982.cairn.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client that talks to {@link CairnAgentServer#run} in another thread over pipes, as an MCP client talks to a server
 * over standard input and output: one JSON-RPC message per line. It checks that every line the server writes is a
 * JSON-RPC message.
 */
final class StdioSession implements AutoCloseable {

    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream clientIn;
    private final BufferedReader fromServer;
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final Thread thread;
    private final AtomicInteger status = new AtomicInteger(Integer.MIN_VALUE);
    private int nextId = 1;

    StdioSession(String... args) throws IOException {
        PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        clientIn = new PipedInputStream(serverOut, 1 << 20);
        fromServer = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        thread = new Thread(() -> status.set(CairnAgentServer.run(args, serverIn, serverOut, err)), "agent-server");
        thread.setDaemon(true);
        thread.start();
    }

    /** What the server wrote to standard error. */
    String errors() {
        return errors.toString(StandardCharsets.UTF_8);
    }

    /** Sends a request and returns its response, which must be a result. */
    JsonNode request(String method, String paramsJson) throws IOException {
        int id = nextId++;
        send("{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": " + paramsJson + "}");
        String line = fromServer.readLine();
        assertThat(line).as("the response to %s; the server said: %s", method, errors()).isNotNull();
        JsonNode response = Json.parse(line);
        assertThat(response.get("jsonrpc").stringValue()).as(line).isEqualTo("2.0");
        assertThat(response.get("id").intValue()).as(line).isEqualTo(id);
        assertThat(response.has("error")).as(line).isFalse();
        return response.get("result");
    }

    void notifyServer(String method) throws IOException {
        send("{\"jsonrpc\": \"2.0\", \"method\": \"" + method + "\"}");
    }

    private void send(String message) throws IOException {
        toServer.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    /** Starts the session as a client does: initialize, and then the notification that it is initialized. */
    JsonNode initialize() throws IOException {
        JsonNode result = request("initialize", "{\"protocolVersion\": \"2025-06-18\", \"capabilities\": {}, "
                + "\"clientInfo\": {\"name\": \"test\", \"version\": \"1\"}}");
        notifyServer("notifications/initialized");
        return result;
    }

    /** Calls a tool and returns the JSON in its text content. */
    JsonNode callTool(String tool, ObjectNode arguments) throws IOException {
        JsonNode result = request("tools/call", "{\"name\": \"" + tool + "\", \"arguments\": " + Json.render(arguments) + "}");
        assertThat(result.get("isError") == null || !result.get("isError").booleanValue()).as(result.toString()).isTrue();
        return Json.parse(result.get("content").get(0).get("text").stringValue());
    }

    /** Closes the input of the server, as a client does when it is done, and waits for the server to stop. */
    int finish() throws IOException, InterruptedException {
        toServer.close();
        thread.join(30_000);
        assertThat(thread.isAlive()).as("the server stops when its input closes").isFalse();
        return status.get();
    }

    @Override
    public void close() throws IOException {
        toServer.close();
        fromServer.close();
    }
}
