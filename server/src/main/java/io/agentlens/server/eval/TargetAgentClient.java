package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replays one case against the target agent over the ADR 0004 protocol:
 * {@code POST {target_url}} with {@code {"run_id", "case_id", "input"}}, expecting
 * {@code {"output", "trace_id"}}. A non-JSON response is taken verbatim as the
 * output. Errors are reported on the result, never thrown, so one failing case
 * cannot abort the run.
 */
@Component
public class TargetAgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvalProperties props;
    private final HttpClient http;

    public TargetAgentClient(EvalProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** One replay outcome; {@code output} and {@code traceId} are null on error. */
    public record ReplayResult(String output, String traceId, String error) {
    }

    public ReplayResult replay(EvalRun run, DatasetCase testCase) {
        String body;
        try {
            body = MAPPER.writeValueAsString(replayBody(run.id(), testCase));
        } catch (IOException e) {
            return new ReplayResult(null, null, "cannot encode replay request: " + e.getMessage());
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(run.targetUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.replayTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            return new ReplayResult(null, null, "invalid target_url: " + e.getMessage());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new ReplayResult(null, null, "target unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReplayResult(null, null, "replay interrupted");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ReplayResult(null, null, "target returned HTTP " + response.statusCode());
        }
        return parseResponse(response.body());
    }

    private static ReplayResult parseResponse(String body) {
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (IOException notJson) {
            return new ReplayResult(body, null, null);
        }
        if (json == null || !json.isObject() || !json.has("output")) {
            return new ReplayResult(body, null, null);
        }
        JsonNode output = json.get("output");
        String traceId = json.path("trace_id").isTextual() ? json.path("trace_id").asText() : null;
        return new ReplayResult(output.isTextual() ? output.asText() : output.toString(), traceId, null);
    }

    private static Map<String, Object> replayBody(String runId, DatasetCase testCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("case_id", testCase.id());
        body.put("input", testCase.input());
        return body;
    }
}
