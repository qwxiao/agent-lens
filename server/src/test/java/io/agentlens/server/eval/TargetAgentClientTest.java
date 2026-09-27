package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TargetAgentClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer target;

    @AfterEach
    void stop() {
        if (target != null) {
            target.stop(0);
        }
    }

    @Test
    void parsesJsonResponseWithOutputAndTraceId() throws IOException {
        targetWith(200, "{\"output\":\"the answer\",\"trace_id\":\"abc123\"}");

        TargetAgentClient.ReplayResult result = client().replay(run(), testCase());

        assertThat(result.error()).isNull();
        assertThat(result.output()).isEqualTo("the answer");
        assertThat(result.traceId()).isEqualTo("abc123");
    }

    @Test
    void treatsNonJsonBodyVerbatimAsOutput() throws IOException {
        targetWith(200, "plain text reply");

        TargetAgentClient.ReplayResult result = client().replay(run(), testCase());

        assertThat(result.error()).isNull();
        assertThat(result.output()).isEqualTo("plain text reply");
        assertThat(result.traceId()).isNull();
    }

    @Test
    void reportsHttpErrorOnResponse() throws IOException {
        targetWith(503, "unavailable");

        TargetAgentClient.ReplayResult result = client().replay(run(), testCase());

        assertThat(result.error()).contains("HTTP 503");
        assertThat(result.output()).isNull();
    }

    @Test
    void sendsReplayProtocolBody() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        target.start();

        client().replay(run(), testCase());

        JsonNode sent = MAPPER.readTree(received.get());
        assertThat(sent.path("run_id").asText()).isEqualTo("run-1");
        assertThat(sent.path("case_id").asText()).isEqualTo("c1");
        assertThat(sent.path("input").path("question").asText()).isEqualTo("capital of france?");
    }

    @Test
    void reportsUnreachableTargetAsError() {
        var client = new TargetAgentClient(new EvalProperties(1000, "", "", ""));
        EvalRun run = new EvalRun("run-1", "d1", "run", "running",
                "http://localhost:1/replay", MAPPER.createArrayNode(), Instant.now(), null);

        TargetAgentClient.ReplayResult result = client.replay(run, testCase());

        assertThat(result.error()).isNotNull();
    }

    private TargetAgentClient client() {
        return new TargetAgentClient(new EvalProperties(60000, "", "", ""));
    }

    private void targetWith(int status, String reply) throws IOException {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/replay", exchange -> {
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        target.start();
    }

    private String targetUrl() {
        return "http://localhost:" + target.getAddress().getPort() + "/replay";
    }

    private EvalRun run() {
        return new EvalRun("run-1", "d1", "run", "running", targetUrl(),
                MAPPER.createArrayNode(), Instant.now(), null);
    }

    private DatasetCase testCase() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("question", "capital of france?");
        ArrayNode tags = MAPPER.createArrayNode();
        return new DatasetCase("c1", "d1", input, "paris", null, tags,
                MAPPER.createObjectNode(), null, null);
    }
}
