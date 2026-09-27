package io.agentlens.server.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dataset management over HTTP against a real PostgreSQL: CRUD, NDJSON case
 * import/export with upsert semantics, and snapshotting a stored trace as a case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EvalDatasetApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MESSAGES_TRACE = """
            {
              "resourceSpans": [{
                "resource": {"attributes": [
                  {"key": "service.name", "value": {"stringValue": "demo-agent"}}
                ]},
                "scopeSpans": [{
                  "scope": {"name": "io.agentlens.demo", "version": "0.1.0"},
                  "spans": [
                    {
                      "traceId": "3af7651916cd43dd8448eb211c80319c",
                      "spanId": "40f8b0c1d2e3f405",
                      "name": "answer-question",
                      "kind": "SPAN_KIND_SERVER",
                      "startTimeUnixNano": "1726542763000000000",
                      "endTimeUnixNano": "1726542763450000000",
                      "attributes": [
                        {"key": "gen_ai.input.messages", "value": {"stringValue": "[{\\"role\\":\\"user\\",\\"content\\":\\"what is 2+2?\\"}]"}}
                      ]
                    },
                    {
                      "traceId": "3af7651916cd43dd8448eb211c80319c",
                      "spanId": "41f8b0c1d2e3f406",
                      "parentSpanId": "40f8b0c1d2e3f405",
                      "name": "chat gpt-4o-mini",
                      "kind": "SPAN_KIND_INTERNAL",
                      "startTimeUnixNano": "1726542763100000000",
                      "endTimeUnixNano": "1726542763180000000",
                      "attributes": [
                        {"key": "gen_ai.usage.input_tokens", "value": {"intValue": "10"}},
                        {"key": "gen_ai.output.messages", "value": {"stringValue": "[{\\"role\\":\\"assistant\\",\\"content\\":\\"4\\"}]"}}
                      ]
                    }
                  ]
                }]
              }]
            }
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    TestRestTemplate rest;

    @Test
    void datasetLifecycleWithNdjsonCases() {
        ResponseEntity<Map> created = rest.postForEntity("/api/datasets",
                Map.of("name", "math", "description", "arithmetic"), Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String datasetId = created.getBody().get("id").toString();
        assertThat(created.getBody().get("name")).isEqualTo("math");

        HttpHeaders ndjson = new HttpHeaders();
        ndjson.setContentType(MediaType.APPLICATION_NDJSON);
        String cases = """
                {"id":"c1","input":{"question":"2+2"},"expected_output":"4","tags":["easy"]}
                {"input":{"question":"2+3"},"expected_output":"5"}
                """;
        ResponseEntity<Map> imported = rest.exchange("/api/datasets/" + datasetId + "/cases",
                org.springframework.http.HttpMethod.PUT, new HttpEntity<>(cases, ndjson), Map.class);
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("upserted")).isEqualTo(2);

        // upsert by stable id keeps one row with updated fields
        ResponseEntity<String> reimported = rest.exchange("/api/datasets/" + datasetId + "/cases",
                org.springframework.http.HttpMethod.PUT, new HttpEntity<>(
                        "{\"id\":\"c1\",\"input\":{\"question\":\"2+2\"},\"expected_output\":\"five\"}", ndjson),
                String.class);
        assertThat(reimported.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> exported = rest.getForEntity("/api/datasets/" + datasetId + "/cases", String.class);
        assertThat(exported.getHeaders().getContentType().toString()).startsWith("application/x-ndjson");
        String[] lines = exported.getBody().trim().split("\n");
        assertThat(lines).hasSize(2);
        JsonNode first = findById(toList(lines), "c1");
        assertThat(first.path("expected_output").asText()).isEqualTo("five");
        assertThat(first.path("tags").get(0).asText()).isEqualTo("easy");

        JsonNode list = rest.getForObject("/api/datasets", JsonNode.class);
        var summaries = new java.util.ArrayList<JsonNode>();
        list.forEach(summaries::add);
        assertThat(findById(summaries, datasetId).path("caseCount").asLong()).isEqualTo(2);

        rest.delete("/api/datasets/" + datasetId);
        assertThat(rest.getForEntity("/api/datasets/" + datasetId, JsonNode.class).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void snapshotStoredTraceAsCase() {
        assertThat(rest.postForEntity("/v1/traces", jsonEntity(MESSAGES_TRACE), String.class)
                .getStatusCode().value()).isEqualTo(200);

        String datasetId = rest.postForEntity("/api/datasets", Map.of("name", "from-prod"), Map.class)
                .getBody().get("id").toString();

        ResponseEntity<Map> snapshot = rest.postForEntity(
                "/api/datasets/" + datasetId + "/cases/from-trace/3af7651916cd43dd8448eb211c80319c", null, Map.class);
        assertThat(snapshot.getStatusCode().value()).isEqualTo(201);
        assertThat(snapshot.getBody().get("id")).isEqualTo("trace-3af7651916cd43dd8448eb211c80319c");
        assertThat(snapshot.getBody().get("source_trace_id")).isEqualTo("3af7651916cd43dd8448eb211c80319c");
        JsonNode input = MAPPER.valueToTree(snapshot.getBody().get("input"));
        assertThat(input.isArray()).isTrue();
        assertThat(input.get(0).path("content").asText()).isEqualTo("what is 2+2?");
        assertThat(snapshot.getBody().get("expected_output")).isEqualTo("4");

        // snapshotting twice updates the same stable case
        rest.postForEntity("/api/datasets/" + datasetId + "/cases/from-trace/3af7651916cd43dd8448eb211c80319c",
                null, Map.class);
        JsonNode list = rest.getForObject("/api/datasets", JsonNode.class);
        var summaries = new java.util.ArrayList<JsonNode>();
        list.forEach(summaries::add);
        assertThat(findById(summaries, datasetId).path("caseCount").asLong()).isEqualTo(1);
    }

    private static JsonNode read(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode findById(java.util.List<JsonNode> list, String id) {
        for (JsonNode item : list) {
            if (item.path("id").asText().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("dataset " + id + " not in list");
    }

    private static java.util.List<JsonNode> toList(String[] lines) {
        var out = new java.util.ArrayList<JsonNode>();
        for (String line : lines) {
            out.add(read(line));
        }
        return out;
    }

    private static HttpEntity<byte[]> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body.getBytes(), headers);
    }
}
