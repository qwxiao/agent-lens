package io.agentlens.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AgentLensCli#run} against a fake platform: the exit-code contract
 * (0 met / 1 not met / 2 operational failure) end to end, including polling.
 */
class CliEndToEndTest {

    private static final String COMPLETED_TWO_OF_FOUR = """
            {"run":{"id":"r1","status":"completed"},
             "totals":{"caseCount":4,"passedCount":2,"passRate":0.5,"avgLatencyMs":100,
                       "inputTokens":0,"outputTokens":0,"costUsd":null},
             "cases":[{"caseId":"c1","passed":true},
                      {"caseId":"c2","passed":true},
                      {"caseId":"c3","passed":false,
                       "scores":{"exact_match":{"passed":false,"detail":"expected 4, got 5"}}},
                      {"caseId":"c4","passed":false,"error":"target agent timed out"}]}
            """;
    private static final String COMPLETED_ALL_PASS = """
            {"run":{"id":"r1","status":"completed"},
             "totals":{"caseCount":4,"passedCount":4,"passRate":1.0,"avgLatencyMs":100,
                       "inputTokens":0,"outputTokens":0,"costUsd":null},
             "cases":[{"caseId":"c1","passed":true},{"caseId":"c2","passed":true},
                      {"caseId":"c3","passed":true},{"caseId":"c4","passed":true}]}
            """;
    private static final String FAILED_RUN = """
            {"run":{"id":"r1","status":"failed"},
             "totals":{"caseCount":0,"passedCount":0,"passRate":0.0,"avgLatencyMs":null,
                       "inputTokens":0,"outputTokens":0,"costUsd":null},
             "cases":[]}
            """;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Fake platform: POST starts run r1; GET serves `runningResponses` running polls, then detail. */
    private FakePlatform fakePlatform(String detailJson, int runningResponses) throws IOException {
        List<String> startBodies = new CopyOnWriteArrayList<>();
        AtomicInteger polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/eval-runs", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                startBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 202, "{\"id\":\"r1\",\"status\":\"running\"}");
                return;
            }
            int poll = polls.incrementAndGet();
            respond(exchange, 200, poll <= runningResponses ? "{\"run\":{\"id\":\"r1\",\"status\":\"running\"}}" : detailJson);
        });
        server.start();
        return new FakePlatform("http://localhost:" + server.getAddress().getPort(), startBodies);
    }

    private record FakePlatform(String base, List<String> startBodies) {
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record CliRun(int exitCode, String out, String err) {
    }

    private CliRun runCli(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = new AgentLensCli().run(args,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        return new CliRun(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void gateNotMetExitsOneAndListsFailures() throws Exception {
        FakePlatform platform = fakePlatform(COMPLETED_TWO_OF_FOUR, 0);
        CliRun result = runCli("eval", "run", "--server", platform.base(),
                "--dataset", "ds1", "--target", "http://agent/replay",
                "--scorers", "exact_match,json_schema", "--gate", "0.9");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("2/4 cases passed (50.0%)"));
        assertTrue(result.out().contains("failed c3: exact_match: expected 4, got 5"));
        assertTrue(result.out().contains("failed c4: target agent timed out"));
        assertTrue(result.err().contains("gate 90.0% not met"));

        String start = platform.startBodies().get(0);
        assertTrue(start.contains("\"datasetId\":\"ds1\""));
        assertTrue(start.contains("\"targetUrl\":\"http://agent/replay\""));
        assertTrue(start.contains("\"scorers\":[\"exact_match\",\"json_schema\"]"));
    }

    @Test
    void gateMetExitsZeroAfterPollingThroughRunning() throws Exception {
        FakePlatform platform = fakePlatform(COMPLETED_ALL_PASS, 2);
        CliRun result = runCli("eval", "run", "--server", platform.base(),
                "--dataset", "ds1", "--target", "http://agent/replay",
                "--scorers", "exact_match", "--gate", "0.9");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("4/4 cases passed (100.0%)"));
        assertTrue(result.out().contains("gate 90.0% met"));
    }

    @Test
    void withoutGateACompletedRunExitsZero() throws Exception {
        FakePlatform platform = fakePlatform(COMPLETED_TWO_OF_FOUR, 0);
        CliRun result = runCli("eval", "run", "--server", platform.base(),
                "--dataset", "ds1", "--target", "http://agent/replay");
        assertEquals(0, result.exitCode());
    }

    @Test
    void failedRunExitsTwo() throws Exception {
        FakePlatform platform = fakePlatform(FAILED_RUN, 0);
        CliRun result = runCli("eval", "run", "--server", platform.base(),
                "--dataset", "ds1", "--target", "http://agent/replay", "--gate", "0.9");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("failed"));
    }

    @Test
    void pollTimeoutExitsTwo() throws Exception {
        FakePlatform platform = fakePlatform(COMPLETED_ALL_PASS, Integer.MAX_VALUE);
        CliRun result = runCli("eval", "run", "--server", platform.base(),
                "--dataset", "ds1", "--target", "http://agent/replay",
                "--scorers", "exact_match", "--timeout", "1");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("still running after 1s"));
    }

    @Test
    void unreachablePlatformExitsTwo() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        CliRun result = runCli("eval", "run", "--server", "http://localhost:" + port,
                "--dataset", "ds1", "--target", "http://agent/replay");
        assertEquals(2, result.exitCode());
    }

    @Test
    void badUsageExitsTwoWithHelp() {
        CliRun result = runCli("eval", "run", "--bogus");
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("usage"));
    }

    @Test
    void helpExitsZero() {
        assertEquals(0, runCli("help").exitCode());
        assertEquals(0, runCli("eval", "run", "--help").exitCode());
        CliRun unknown = runCli("deploy");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.err().contains("unknown command"));
    }
}
