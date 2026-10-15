package io.github.enricoag1982.cairn.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client that talks to {@link CairnAgentServer#run} in another thread over pipes, as an MCP client talks to a server
 * over standard input and output: one JSON-RPC message per line. It checks that every line the server writes is a
 * JSON-RPC message.
 *
 * <p>The pipes are {@link Pipe}s and not {@link java.io.PipedInputStream}s: a piped stream remembers the last thread
 * that wrote to it and fails a read with "Write end dead" once that thread has ended and the buffer is empty. The MCP
 * server writes its responses from pool threads that end between messages, so with piped streams a read fails
 * depending on timing.
 */
final class StdioSession implements AutoCloseable {

    private final OutputStream toServer;
    private final BufferedReader fromServer;
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final Thread thread;
    private final AtomicInteger status = new AtomicInteger(Integer.MIN_VALUE);
    private int nextId = 1;

    StdioSession(String... args) throws IOException {
        Pipe requests = Pipe.open();
        Pipe responses = Pipe.open();
        toServer = Channels.newOutputStream(requests.sink());
        InputStream serverIn = Channels.newInputStream(requests.source());
        OutputStream serverOut = Channels.newOutputStream(responses.sink());
        fromServer = new BufferedReader(new InputStreamReader(Channels.newInputStream(responses.source()),
                StandardCharsets.UTF_8));
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        thread = new Thread(() -> serve(args, serverIn, serverOut, err), "agent-server");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs the server and then closes its ends of the pipes, as the end of a process does: the client sees the end. */
    private void serve(String[] args, InputStream in, OutputStream out, PrintStream err) {
        try (in; out) {
            status.set(CairnAgentServer.run(args, in, out, err));
        } catch (IOException e) {
            err.println("Cannot close the pipes of the server: " + e);
        }
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
