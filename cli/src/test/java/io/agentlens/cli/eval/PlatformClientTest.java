package io.agentlens.cli.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformClientTest {

    private static final String RUN_DETAIL = """
            {"run":{"id":"run-1","status":"completed","extra":"ignored"},
             "totals":{"caseCount":2,"passedCount":1,"passRate":0.5,"avgLatencyMs":120,
                       "inputTokens":1000,"outputTokens":100,"costUsd":0.00042},
             "cases":[{"caseId":"c1","passed":true,"error":null,
                       "scores":{"exact_match":{"passed":true,"detail":"ok"}},
                       "latencyMs":9,"output":"4","traceId":"abc","cost":{"costUsd":0.00021}},
                      {"caseId":"c2","passed":false,"error":null,
                       "scores":{"exact_match":{"passed":false,"detail":"expected five, got four"}}}]}
            """;

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastStartBody = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/eval-runs", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                lastStartBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String datasetId = lastStartBody.get().contains("boom") ? "boom" : "ds1";
                int status = datasetId.equals("boom") ? 400 : 202;
                String body = datasetId.equals("boom")
                        ? "{\"error\":\"dataset not found: boom\"}"
                        : "{\"id\":\"run-1\",\"status\":\"running\"}";
                respond(exchange, status, body);
                return;
            }
            if (path.endsWith("/api/eval-runs/run-1")) {
                respond(exchange, 200, RUN_DETAIL);
            } else if (path.endsWith("/api/eval-runs/bad")) {
                respond(exchange, 200, "this is not json");
            } else {
                respond(exchange, 404, "{\"error\":\"run not found\"}");
            }
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void startRunReturnsTheRunId() throws Exception {
        PlatformClient client = new PlatformClient(base);
        assertEquals("run-1", client.startRun(new PlatformClient.StartRequest(
                "ds1", "nightly", "http://agent/replay", java.util.List.of("exact_match"))));
        String sent = lastStartBody.get();
        assertTrue(sent.contains("\"datasetId\":\"ds1\""));
        assertTrue(sent.contains("\"targetUrl\":\"http://agent/replay\""));
        assertTrue(sent.contains("\"scorers\":[\"exact_match\"]"));
    }

    @Test
    void startRunOmitsNullFields() throws Exception {
        PlatformClient client = new PlatformClient(base);
        client.startRun(new PlatformClient.StartRequest("ds1", null, "http://agent/replay", null));
        assertTrue(!lastStartBody.get().contains("name"));
    }

    @Test
    void startRunSurfacesHttpErrorsWithThePlatformMessage() {
        PlatformClient client = new PlatformClient(base);
        PlatformClient.PlatformException e = assertThrows(PlatformClient.PlatformException.class,
                () -> client.startRun(new PlatformClient.StartRequest(
                        "boom", null, "http://agent/replay", null)));
        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("dataset not found: boom"));
    }

    @Test
    void getRunParsesTheDetailDocument() throws Exception {
        RunDetail detail = new PlatformClient(base).getRun("run-1");
        assertEquals("run-1", detail.run().id());
        assertEquals("completed", detail.run().status());
        assertEquals(2, detail.totals().caseCount());
        assertEquals(0.5, detail.totals().passRate());
        assertEquals(0, detail.totals().costUsd().compareTo(new java.math.BigDecimal("0.00042")));
        assertEquals(2, detail.cases().size());
        assertEquals("c2", detail.cases().get(1).caseId());
    }

    @Test
    void getRunSurfacesHttpErrors() {
        PlatformClient.PlatformException e = assertThrows(PlatformClient.PlatformException.class,
                () -> new PlatformClient(base).getRun("missing"));
        assertTrue(e.getMessage().contains("HTTP 404"));
    }

    @Test
    void getRunRejectsMalformedJson() {
        assertThrows(PlatformClient.PlatformException.class, () -> new PlatformClient(base).getRun("bad"));
    }

    @Test
    void unreachablePlatformThrows() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertThrows(IOException.class, () -> new PlatformClient("http://localhost:" + port).getRun("run-1"));
    }
}
