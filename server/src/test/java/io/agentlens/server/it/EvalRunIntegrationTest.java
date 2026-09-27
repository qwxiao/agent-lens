package io.agentlens.server.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full evaluation flow over HTTP against a real PostgreSQL: dataset with two cases,
 * a stub target agent that reports its spans to the platform while replaying (with
 * {@code agentlens.eval.*} attributes, as a real instrumented agent would), async
 * run execution, scoring, cost attribution over both the trace_id and the attribute
 * channel, and the comparison of two runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalRunIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String C1_TRACE = "4af7651916cd43dd8448eb211c80319c";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int platformPort;

    HttpServer targetAgent;

    @BeforeAll
    void startStubAgent() throws Exception {
        targetAgent = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        targetAgent.createContext("/replay", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String runId = request.path("run_id").asText();
            String caseId = request.path("case_id").asText();
            // the replayed agent reports its span to the platform synchronously, like
            // an SDK-instrumented agent: tagged for c2 (attribute channel), own trace for c1
            if (caseId.equals("c1")) {
                reportSpan(C1_TRACE, runId, caseId, null);
            } else {
                reportSpan(hex(16), runId, caseId, "c2");
            }
            String output = caseId.equals("c1") ? "4" : "{\"greeting\":\"hi\"}";
            String response = caseId.equals("c1")
                    ? MAPPER.writeValueAsString(Map.of("output", output, "trace_id", C1_TRACE))
                    : MAPPER.writeValueAsString(Map.of("output", output));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        targetAgent.start();
    }

    @AfterAll
    void stopStubAgent() {
        targetAgent.stop(0);
    }

    private void reportSpan(String traceId, String runId, String caseId, String taggedCaseId) {
        StringBuilder attrs = new StringBuilder();
        attrs.append("{\"key\":\"gen_ai.request.model\",\"value\":{\"stringValue\":\"gpt-4o-mini\"}},");
        attrs.append("{\"key\":\"gen_ai.usage.input_tokens\",\"value\":{\"intValue\":\"1000\"}},");
        attrs.append("{\"key\":\"gen_ai.usage.output_tokens\",\"value\":{\"intValue\":\"100\"}}");
        if (taggedCaseId != null) {
            attrs.append(",{\"key\":\"agentlens.eval.run_id\",\"value\":{\"stringValue\":\"%s\"}}"
                    .formatted(runId));
            attrs.append(",{\"key\":\"agentlens.eval.case_id\",\"value\":{\"stringValue\":\"%s\"}}"
                    .formatted(taggedCaseId));
        }
        long now = System.currentTimeMillis() * 1_000_000L;
        String payload = """
                {"resourceSpans":[{"resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"replayed-agent"}}]},
                  "scopeSpans":[{"scope":{"name":"eval.test","version":"0.1.0"},"spans":[
                    {"traceId":"%s","spanId":"%s","name":"chat gpt-4o-mini","kind":"SPAN_KIND_INTERNAL",
                     "startTimeUnixNano":"%d","endTimeUnixNano":"%d","attributes":[%s]}]}]}]}
                """.formatted(traceId, hex(8), now, now + 50_000_000L, attrs);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("http://localhost:" + platformPort + "/v1/traces",
                new HttpEntity<>(payload.getBytes(StandardCharsets.UTF_8), headers), String.class);
    }

    private static String hex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private String datasetWithCases() {
        String datasetId = rest.postForEntity("/api/datasets", Map.of("name", "math-eval"), Map.class)
                .getBody().get("id").toString();
        HttpHeaders ndjson = new HttpHeaders();
        ndjson.setContentType(MediaType.APPLICATION_NDJSON);
        String cases = """
                {"id":"c1","input":{"question":"2+2"},"expected_output":"4"}
                {"id":"c2","input":{"question":"say hi"},"json_schema":{"type":"object","required":["greeting"]}}
                """;
        ResponseEntity<String> imported = rest.exchange("/api/datasets/" + datasetId + "/cases",
                org.springframework.http.HttpMethod.PUT, new HttpEntity<>(cases, ndjson), String.class);
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        return datasetId;
    }

    private String startRun(String datasetId) {
        ResponseEntity<Map> started = rest.postForEntity("/api/eval-runs", Map.of(
                "datasetId", datasetId,
                "name", "replay " + datasetId,
                "targetUrl", "http://localhost:" + targetAgent.getAddress().getPort() + "/replay",
                "scorers", List.of("exact_match", "json_schema")), Map.class);
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        return started.getBody().get("id").toString();
    }

    private JsonNode awaitCompleted(String runId) {
        var detail = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            JsonNode current = rest.getForObject("/api/eval-runs/" + runId, JsonNode.class);
            assertThat(current.path("run").path("status").asText()).isEqualTo("completed");
            detail.set(current);
        });
        return detail.get();
    }

    @Test
    void runReplaysScoresAndAttributesCost() {
        String datasetId = datasetWithCases();
        String runId = startRun(datasetId);
        JsonNode detail = awaitCompleted(runId);

        assertThat(detail.path("run").path("status").asText()).isEqualTo("completed");
        JsonNode totals = detail.path("totals");
        assertThat(totals.path("caseCount").asLong()).isEqualTo(2);
        assertThat(totals.path("passedCount").asLong()).isEqualTo(2);
        assertThat(totals.path("passRate").asDouble()).isEqualTo(1.0);
        // both channels land on 1000*0.15/1M + 100*0.60/1M = 0.00021
        assertThat(totals.path("costUsd").decimalValue()).isEqualByComparingTo("0.000420");

        JsonNode cases = detail.path("cases");
        assertThat(cases).hasSize(2);
        JsonNode c1 = findCase(cases, "c1");
        assertThat(c1.path("passed").asBoolean()).isTrue();
        assertThat(c1.path("output").asText()).isEqualTo("4");
        assertThat(c1.path("traceId").asText()).isEqualTo(C1_TRACE);
        assertThat(c1.path("scores").path("exact_match").path("passed").asBoolean()).isTrue();
        assertThat(c1.path("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(c1.path("cost").path("costUsd").decimalValue()).isEqualByComparingTo("0.000210");

        JsonNode c2 = findCase(cases, "c2");
        assertThat(c2.path("passed").asBoolean()).isTrue();
        assertThat(c2.path("scores").path("json_schema").path("passed").asBoolean()).isTrue();
        assertThat(c2.path("traceId").isNull()).isTrue();
        assertThat(c2.path("cost").path("costUsd").decimalValue()).isEqualByComparingTo("0.000210");

        JsonNode runs = rest.getForObject("/api/eval-runs", JsonNode.class);
        assertThat(runs.findValues("id").toString()).contains(runId);
    }

    @Test
    void compareShowsCaseLevelRegression() {
        String datasetId = datasetWithCases();
        String runA = startRun(datasetId);
        awaitCompleted(runA);

        // change the expectation and re-run the same dataset
        HttpHeaders ndjson = new HttpHeaders();
        ndjson.setContentType(MediaType.APPLICATION_NDJSON);
        rest.exchange("/api/datasets/" + datasetId + "/cases", org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>("{\"id\":\"c1\",\"input\":{\"question\":\"2+2\"},\"expected_output\":\"five\"}\n", ndjson),
                String.class);
        String runB = startRun(datasetId);
        JsonNode detailB = awaitCompleted(runB);

        assertThat(detailB.path("totals").path("passedCount").asLong()).isEqualTo(1);

        JsonNode comparison = rest.getForObject("/api/eval-runs/" + runA + "/compare/" + runB, JsonNode.class);
        assertThat(comparison.path("runA").path("run").path("id").asText()).isEqualTo(runA);
        assertThat(comparison.path("runB").path("run").path("id").asText()).isEqualTo(runB);
        JsonNode cases = comparison.path("cases");
        assertThat(cases).hasSize(2);
        JsonNode c1 = findCase(cases, "c1");
        assertThat(c1.path("passedA").asBoolean()).isTrue();
        assertThat(c1.path("passedB").asBoolean()).isFalse();
        JsonNode c2 = findCase(cases, "c2");
        assertThat(c2.path("passedA").asBoolean()).isTrue();
        assertThat(c2.path("passedB").asBoolean()).isTrue();
    }

    private static JsonNode findCase(JsonNode cases, String caseId) {
        for (JsonNode entry : cases) {
            if (entry.path("caseId").asText().equals(caseId)) {
                return entry;
            }
        }
        throw new AssertionError("case " + caseId + " not found");
    }
}
