package io.agentlens.server.it;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP against a real PostgreSQL: ingest OTLP/JSON, then read the
 * trace back through the query API with waterfall and cost fields populated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TraceIngestIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    TestRestTemplate rest;

    private static final String THREE_SPAN_TRACE = """
            {
              "resourceSpans": [{
                "resource": {"attributes": [
                  {"key": "service.name", "value": {"stringValue": "demo-agent"}}
                ]},
                "scopeSpans": [{
                  "scope": {"name": "io.agentlens.demo", "version": "0.1.0"},
                  "spans": [
                    {
                      "traceId": "0af7651916cd43dd8448eb211c80319c",
                      "spanId": "00f8b0c1d2e3f405",
                      "name": "agent.run",
                      "kind": "SPAN_KIND_SERVER",
                      "startTimeUnixNano": "1726542763000000000",
                      "endTimeUnixNano": "1726542763450000000",
                      "status": {"code": "STATUS_CODE_OK"}
                    },
                    {
                      "traceId": "0af7651916cd43dd8448eb211c80319c",
                      "spanId": "11f8b0c1d2e3f406",
                      "parentSpanId": "00f8b0c1d2e3f405",
                      "name": "chat gpt-4o-mini",
                      "kind": "SPAN_KIND_INTERNAL",
                      "startTimeUnixNano": "1726542763100000000",
                      "endTimeUnixNano": "1726542763180000000",
                      "attributes": [
                        {"key": "gen_ai.system", "value": {"stringValue": "openai"}},
                        {"key": "gen_ai.operation.name", "value": {"stringValue": "chat"}},
                        {"key": "gen_ai.request.model", "value": {"stringValue": "gpt-4o-mini"}},
                        {"key": "gen_ai.usage.input_tokens", "value": {"intValue": "1240"}},
                        {"key": "gen_ai.usage.output_tokens", "value": {"intValue": "356"}}
                      ]
                    },
                    {
                      "traceId": "0af7651916cd43dd8448eb211c80319c",
                      "spanId": "22f8b0c1d2e3f407",
                      "parentSpanId": "00f8b0c1d2e3f405",
                      "name": "execute_tool get_weather",
                      "kind": "SPAN_KIND_INTERNAL",
                      "startTimeUnixNano": "1726542763200000000",
                      "endTimeUnixNano": "1726542763250000000",
                      "attributes": [
                        {"key": "gen_ai.operation.name", "value": {"stringValue": "execute_tool"}},
                        {"key": "gen_ai.tool.name", "value": {"stringValue": "get_weather"}},
                        {"key": "gen_ai.tool.call.id", "value": {"stringValue": "call_abc"}}
                      ]
                    }
                  ]
                }]
              }]
            }
            """;

    @Test
    void ingestedTraceIsQueryableWithWaterfallAndCost() {
        assertThat(postThreeSpanTrace().getStatusCode().value()).isEqualTo(200);

        JsonNode list = rest.getForObject("/api/traces", JsonNode.class);
        assertThat(list.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode summary = findTrace(list.path("traces"), "0af7651916cd43dd8448eb211c80319c");
        assertThat(summary).isNotNull();
        assertThat(summary.path("rootSpanName").asText()).isEqualTo("agent.run");
        assertThat(summary.path("spanCount").asInt()).isEqualTo(3);
        assertThat(summary.path("durationMs").asLong()).isEqualTo(450);
        assertThat(summary.path("services").get(0).asText()).isEqualTo("demo-agent");
        assertThat(summary.path("models").get(0).asText()).isEqualTo("gpt-4o-mini");
        assertThat(summary.path("inputTokens").asLong()).isEqualTo(1240);
        assertThat(summary.path("outputTokens").asLong()).isEqualTo(356);
        // 1240 * 0.15 / 1M + 356 * 0.60 / 1M = 0.0003996
        assertThat(summary.path("costUsd").decimalValue()).isEqualByComparingTo("0.000400");

        JsonNode detail = rest.getForObject("/api/traces/0af7651916cd43dd8448eb211c80319c", JsonNode.class);
        JsonNode spans = detail.path("spans");
        assertThat(spans).hasSize(3);
        assertThat(spans.get(0).path("name").asText()).isEqualTo("agent.run");

        JsonNode llm = findSpan(spans, "11f8b0c1d2e3f406");
        assertThat(llm.path("category").asText()).isEqualTo("llm");
        assertThat(llm.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(llm.path("system").asText()).isEqualTo("openai");
        assertThat(llm.path("inputTokens").asLong()).isEqualTo(1240);
        assertThat(llm.path("outputTokens").asLong()).isEqualTo(356);
        assertThat(llm.path("durationMs").asLong()).isEqualTo(80);
        assertThat(llm.path("costUsd").decimalValue()).isEqualByComparingTo("0.000400");
        // gen_ai.* attributes survive ingestion verbatim
        assertThat(llm.path("attributes").path("gen_ai.request.model").asText()).isEqualTo("gpt-4o-mini");

        JsonNode tool = findSpan(spans, "22f8b0c1d2e3f407");
        assertThat(tool.path("category").asText()).isEqualTo("tool");
        assertThat(tool.path("toolName").asText()).isEqualTo("get_weather");
        assertThat(tool.path("costUsd").isNull()).isTrue();
    }

    @Test
    void duplicateIngestIsIdempotent() {
        assertThat(postThreeSpanTrace().getStatusCode().value()).isEqualTo(200);
        assertThat(postThreeSpanTrace().getStatusCode().value()).isEqualTo(200);

        JsonNode detail = rest.getForObject("/api/traces/0af7651916cd43dd8448eb211c80319c", JsonNode.class);
        assertThat(detail.path("spanCount").asInt()).isEqualTo(3);
    }

    @Test
    void acceptsGzipEncodedPayload() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        String payload = THREE_SPAN_TRACE.replace("0af7651916cd43dd8448eb211c80319c", "1af7651916cd43dd8448eb211c80319c")
                .replace("00f8b0c1d2e3f405", "30f8b0c1d2e3f405")
                .replace("11f8b0c1d2e3f406", "31f8b0c1d2e3f406")
                .replace("22f8b0c1d2e3f407", "32f8b0c1d2e3f407");
        HttpEntity<byte[]> request = new HttpEntity<>(gzip(payload.getBytes(StandardCharsets.UTF_8)), headers);

        ResponseEntity<String> response = rest.postForEntity("/v1/traces", request, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{}");
        JsonNode detail = rest.getForObject("/api/traces/1af7651916cd43dd8448eb211c80319c", JsonNode.class);
        assertThat(detail.path("spanCount").asInt()).isEqualTo(3);
    }

    private ResponseEntity<String> postThreeSpanTrace() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/traces",
                new HttpEntity<>(THREE_SPAN_TRACE.getBytes(StandardCharsets.UTF_8), headers), String.class);
    }

    private static JsonNode findTrace(JsonNode traces, String traceId) {
        for (JsonNode trace : traces) {
            if (trace.path("traceId").asText().equals(traceId)) {
                return trace;
            }
        }
        return null;
    }

    private static JsonNode findSpan(JsonNode spans, String spanId) {
        for (JsonNode span : spans) {
            if (span.path("spanId").asText().equals(spanId)) {
                return span;
            }
        }
        return null;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
