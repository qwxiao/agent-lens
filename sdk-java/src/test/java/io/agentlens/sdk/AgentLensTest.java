package io.agentlens.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full bootstrap: one call to {@link AgentLens#create} must land spans on the
 * platform with the service name, and {@link EvalScope} must tag replayed spans with
 * the {@code agentlens.eval.*} attribution attributes — including the flush on close.
 */
class AgentLensTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BlockingQueue<String> BODIES = new LinkedBlockingQueue<>();
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @AfterEach
    void drain() {
        BODIES.clear();
        EvalScope.clear();
    }

    @Test
    void createTagsAndFlushesSpans() throws Exception {
        try (AgentLens lens = AgentLens.create(base, "smoke-service")) {
            lens.openTelemetry().getTracer("app").spanBuilder("plain").startSpan().end();
            EvalScope.set("run-1", "case-9");
            try {
                lens.openTelemetry().getTracer("app").spanBuilder("tagged").startSpan().end();
            } finally {
                EvalScope.clear();
            }
        }

        // close() flushed the batch: expect one export containing both spans
        String body = BODIES.poll(5, TimeUnit.SECONDS);
        assertTrue(body != null, "no export arrived within 5s");
        JsonNode spans = MAPPER.readTree(body)
                .path("resourceSpans").get(0)
                .path("scopeSpans").get(0)
                .path("spans");

        JsonNode plain = spanByName(spans, "plain");
        JsonNode tagged = spanByName(spans, "tagged");

        JsonNode resource = MAPPER.readTree(body).path("resourceSpans").get(0).path("resource");
        assertEquals("smoke-service", resourceAttr(resource, "service.name"));

        assertNull(attrValue(plain, "agentlens.eval.run_id"), "plain span must not be tagged");
        assertEquals("run-1", attrValue(tagged, "agentlens.eval.run_id").path("stringValue").asText());
        assertEquals("case-9", attrValue(tagged, "agentlens.eval.case_id").path("stringValue").asText());
    }

    private static JsonNode spanByName(JsonNode spans, String name) {
        for (JsonNode span : spans) {
            if (span.path("name").asText().equals(name)) {
                return span;
            }
        }
        throw new AssertionError("span not found: " + name);
    }

    private static String resourceAttr(JsonNode resource, String key) {
        for (JsonNode entry : resource.path("attributes")) {
            if (entry.path("key").asText().equals(key)) {
                return entry.path("value").path("stringValue").asText();
            }
        }
        throw new AssertionError("resource attribute not found: " + key);
    }

    private static JsonNode attrValue(JsonNode span, String key) {
        for (JsonNode entry : span.path("attributes")) {
            if (entry.path("key").asText().equals(key)) {
                return entry.path("value");
            }
        }
        return null;
    }
}
