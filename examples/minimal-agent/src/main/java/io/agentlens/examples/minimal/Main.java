package io.agentlens.examples.minimal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Reports one fake agent run to agent-lens over OTLP/HTTP with JSON encoding —
 * no OpenTelemetry SDK, no dependencies: just the JDK. The run is a root agent
 * span with two LLM calls and one tool call in between, carrying {@code gen_ai.*}
 * attributes per the OpenTelemetry GenAI semantic conventions.
 *
 * <p>Start the platform first, then run:
 * {@code ./mvnw -pl examples/minimal-agent compile exec:java}
 * (override the endpoint with the AGENTLENS_URL environment variable).
 */
public final class Main {

    private static final String ENDPOINT =
            System.getenv().getOrDefault("AGENTLENS_URL", "http://localhost:8080/v1/traces");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis() * 1_000_000L;
        long ns = 1_000_000L; // one millisecond in nanos
        String traceId = hex(16);
        String rootSpanId = hex(8);
        String firstLlmSpanId = hex(8);
        String toolSpanId = hex(8);
        String secondLlmSpanId = hex(8);

        String resource = """
                {"resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"demo-agent"}},
                    {"key":"service.version","value":{"stringValue":"0.1.0"}}]},
                  "scopeSpans":[{"scope":{"name":"io.agentlens.examples.minimal","version":"0.1.0"},
                  "spans":[""";
        String spans = String.join(",",
                span(traceId, rootSpanId, null, "answer-question", "SPAN_KIND_SERVER",
                        t0, t0 + 2_400 * ns, "{}"),
                span(traceId, firstLlmSpanId, rootSpanId, "chat gpt-4o-mini", "SPAN_KIND_INTERNAL",
                        t0 + 50 * ns, t0 + 750 * ns, llmAttributes("gpt-4o-mini", 1240, 356)),
                span(traceId, toolSpanId, rootSpanId, "execute_tool get_weather", "SPAN_KIND_INTERNAL",
                        t0 + 850 * ns, t0 + 1_150 * ns, toolAttributes()),
                span(traceId, secondLlmSpanId, rootSpanId, "chat gpt-4o-mini", "SPAN_KIND_INTERNAL",
                        t0 + 1_250 * ns, t0 + 2_300 * ns, llmAttributes("gpt-4o-mini", 1980, 512)));
        String payload = "{\"resourceSpans\":[" + resource + spans + "]}]}]}";

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.printf("POST %s -> HTTP %d%n", ENDPOINT, response.statusCode());
            System.out.println(response.body());
            if (response.statusCode() == 200) {
                System.out.println("Trace sent. Open the agent-lens dashboard to see the waterfall and cost.");
            }
        }
    }

    private static String span(String traceId, String spanId, String parentSpanId, String name,
                               String kind, long startNanos, long endNanos, String attributes) {
        String parent = parentSpanId == null ? "" : "\"parentSpanId\":\"%s\",".formatted(parentSpanId);
        return """
                {"traceId":"%s","spanId":"%s",%s"name":"%s","kind":"%s",
                 "startTimeUnixNano":"%d","endTimeUnixNano":"%d",
                 "attributes":[%s],"status":{"code":"STATUS_CODE_OK"}}"""
                .formatted(traceId, spanId, parent, name, kind, startNanos, endNanos, attributes);
    }

    private static String llmAttributes(String model, long inputTokens, long outputTokens) {
        return """
                {"key":"gen_ai.system","value":{"stringValue":"openai"}},
                {"key":"gen_ai.operation.name","value":{"stringValue":"chat"}},
                {"key":"gen_ai.request.model","value":{"stringValue":"%s"}},
                {"key":"gen_ai.request.temperature","value":{"doubleValue":0.7}},
                {"key":"gen_ai.usage.input_tokens","value":{"intValue":"%d"}},
                {"key":"gen_ai.usage.output_tokens","value":{"intValue":"%d"}}"""
                .formatted(model, inputTokens, outputTokens);
    }

    private static String toolAttributes() {
        return """
                {"key":"gen_ai.operation.name","value":{"stringValue":"execute_tool"}},
                {"key":"gen_ai.tool.name","value":{"stringValue":"get_weather"}},
                {"key":"gen_ai.tool.call.id","value":{"stringValue":"call_demo_1"}},
                {"key":"gen_ai.tool.description","value":{"stringValue":"Look up current weather for a city"}}""";
    }

    private static String hex(int byteCount) {
        byte[] raw = new byte[byteCount];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private Main() {
    }
}
