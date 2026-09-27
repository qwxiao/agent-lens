package io.agentlens.server.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentlens.server.store.SpanRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpTraceRequestParserTest {

    private final OtlpTraceRequestParser parser = new OtlpTraceRequestParser();

    @Test
    void parsesCanonicalOtlpJson() throws IOException {
        String payload = """
                {
                  "resourceSpans": [{
                    "resource": {"attributes": [
                      {"key": "service.name", "value": {"stringValue": "demo-agent"}},
                      {"key": "deployment.environment", "value": {"stringValue": "dev"}}
                    ]},
                    "scopeSpans": [{
                      "scope": {"name": "io.agentlens.demo", "version": "0.1.0"},
                      "spans": [{
                        "traceId": "5b8efff798038103d269b633813fc60c",
                        "spanId": "eee19b7ec3c1b174",
                        "name": "agent.run",
                        "kind": "SPAN_KIND_SERVER",
                        "startTimeUnixNano": "1726542763000000000",
                        "endTimeUnixNano": "1726542764000000000",
                        "attributes": [
                          {"key": "gen_ai.system", "value": {"stringValue": "openai"}},
                          {"key": "gen_ai.usage.input_tokens", "value": {"intValue": "1240"}},
                          {"key": "gen_ai.request.temperature", "value": {"doubleValue": 0.7}},
                          {"key": "gen_ai.request.stop_sequences", "value": {"arrayValue":
                            {"values": [{"stringValue": "\\n"}, {"stringValue": "END"}]}}},
                          {"key": "gen_ai.request.metadata", "value": {"kvlistValue":
                            {"values": [{"key": "user", "value": {"stringValue": "u1"}}]}}},
                          {"key": "session.id", "value": {"boolValue": false}}
                        ],
                        "events": [
                          {"timeUnixNano": "1726542763500000000", "name": "gen_ai.choice",
                           "attributes": [{"key": "gen_ai.choice.index", "value": {"intValue": "0"}}]}
                        ],
                        "links": [
                          {"traceId": "0af7651916cd43dd8448eb211c80319c", "spanId": "1234567890abcdef"}
                        ],
                        "status": {"code": "STATUS_CODE_OK", "message": ""}
                      }]
                    }]
                  }]
                }
                """;

        List<SpanRecord> spans = parser.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(spans).hasSize(1);
        SpanRecord span = spans.get(0);
        assertThat(span.traceId()).isEqualTo("5b8efff798038103d269b633813fc60c");
        assertThat(span.spanId()).isEqualTo("eee19b7ec3c1b174");
        assertThat(span.parentSpanId()).isNull();
        assertThat(span.name()).isEqualTo("agent.run");
        assertThat(span.spanKind()).isEqualTo("SERVER");
        assertThat(span.statusCode()).isEqualTo("OK");
        assertThat(span.startTime()).isEqualTo(Instant.ofEpochSecond(1726542763L));
        assertThat(span.endTime()).isEqualTo(Instant.ofEpochSecond(1726542764L));
        assertThat(span.serviceName()).isEqualTo("demo-agent");
        assertThat(span.scopeName()).isEqualTo("io.agentlens.demo");
        assertThat(span.scopeVersion()).isEqualTo("0.1.0");

        JsonNode attributes = span.attributes();
        assertThat(attributes.path("gen_ai.system").asText()).isEqualTo("openai");
        assertThat(attributes.path("gen_ai.usage.input_tokens").asLong()).isEqualTo(1240);
        assertThat(attributes.path("gen_ai.request.temperature").asDouble()).isEqualTo(0.7);
        assertThat(attributes.path("gen_ai.request.stop_sequences").get(0).asText()).isEqualTo("\n");
        assertThat(attributes.path("gen_ai.request.metadata").path("user").asText()).isEqualTo("u1");
        assertThat(attributes.path("session.id").asBoolean()).isFalse();

        assertThat(span.events().get(0).path("name").asText()).isEqualTo("gen_ai.choice");
        assertThat(span.links().get(0).path("traceId").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void decodesBase64IdsFromOlderExporters() throws IOException {
        String traceIdHex = "5b8efff798038103d269b633813fc60c";
        String spanIdHex = "eee19b7ec3c1b174";
        String payload = """
                {"resourceSpans": [{"resource": {"attributes": []}, "scopeSpans": [{"spans": [{
                   "traceId": "%s", "spanId": "%s", "name": "s",
                   "startTimeUnixNano": "1000000000", "endTimeUnixNano": "2000000000"
                }]}]}]}
                """.formatted(
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(traceIdHex)),
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(spanIdHex)));

        SpanRecord span = parser.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))).get(0);

        assertThat(span.traceId()).isEqualTo(traceIdHex);
        assertThat(span.spanId()).isEqualTo(spanIdHex);
    }

    @Test
    void mapsNumericKindAndStatusEnums() throws IOException {
        String payload = """
                {"resourceSpans": [{"scopeSpans": [{"spans": [{
                   "traceId": "5b8efff798038103d269b633813fc60c", "spanId": "eee19b7ec3c1b174",
                   "name": "s", "kind": 2,
                   "startTimeUnixNano": "1000000000", "endTimeUnixNano": "2000000000",
                   "status": {"code": 2, "message": "boom"}
                }]}]}]}
                """;

        SpanRecord span = parser.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))).get(0);

        assertThat(span.spanKind()).isEqualTo("SERVER");
        assertThat(span.statusCode()).isEqualTo("ERROR");
        assertThat(span.statusMessage()).isEqualTo("boom");
        assertThat(span.serviceName()).isNull();
        assertThat(span.scopeName()).isNull();
    }

    @Test
    void skipsSpansWithoutIdentifiersOrTimestamps() throws IOException {
        String payload = """
                {
                  "resourceSpans": [{
                    "scopeSpans": [{
                      "spans": [
                        {"name": "no ids", "startTimeUnixNano": "1", "endTimeUnixNano": "2"},
                        {"traceId": "5b8efff798038103d269b633813fc60c", "name": "no span id",
                         "startTimeUnixNano": "1", "endTimeUnixNano": "2"},
                        {"traceId": "5b8efff798038103d269b633813fc60c", "spanId": "eee19b7ec3c1b174",
                         "name": "no end", "startTimeUnixNano": "1"},
                        {"traceId": "5b8efff798038103d269b633813fc60c", "spanId": "aaa19b7ec3c1b174",
                         "name": "ok", "startTimeUnixNano": "1", "endTimeUnixNano": "2"}
                      ]
                    }]
                  }]
                }
                """;

        List<SpanRecord> spans = parser.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).name()).isEqualTo("ok");
    }

    @Test
    void emptyRequestYieldsNoSpans() throws IOException {
        List<SpanRecord> spans = parser.parse(
                new ByteArrayInputStream("{\"resourceSpans\": []}".getBytes(StandardCharsets.UTF_8)));

        assertThat(spans).isEmpty();
    }
}
