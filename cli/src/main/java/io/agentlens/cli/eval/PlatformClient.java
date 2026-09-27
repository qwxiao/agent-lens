package io.agentlens.cli.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for the platform eval API: starts runs ({@code POST /api/eval-runs})
 * and polls run detail ({@code GET /api/eval-runs/{id}}). Transport is the JDK
 * HttpClient; every non-2xx or malformed response raises {@link PlatformException}.
 */
public final class PlatformClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ERROR_SNIPPET = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;

    public PlatformClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String startRun(StartRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/eval-runs",
                MAPPER.writeValueAsString(request));
        if (response.statusCode() / 100 != 2) {
            throw new PlatformException("starting the run failed: HTTP " + response.statusCode()
                    + " — " + snippet(response.body()));
        }
        String id = MAPPER.readTree(response.body()).path("id").asText();
        if (id.isBlank()) {
            throw new PlatformException("platform returned no run id: " + snippet(response.body()));
        }
        return id;
    }

    public RunDetail getRun(String runId) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/eval-runs/" + runId, null);
        if (response.statusCode() / 100 != 2) {
            throw new PlatformException("fetching run " + runId + " failed: HTTP "
                    + response.statusCode() + " — " + snippet(response.body()));
        }
        try {
            return MAPPER.readValue(response.body(), RunDetail.class);
        } catch (JsonProcessingException e) {
            throw new PlatformException("platform response is not valid run detail: "
                    + snippet(response.body()), e);
        }
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT);
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String snippet(String body) {
        if (body == null) {
            return "(no body)";
        }
        String flat = body.strip();
        return flat.length() <= ERROR_SNIPPET ? flat : flat.substring(0, ERROR_SNIPPET) + "…";
    }

    /** Mirrors {@code EvalRunner.StartRequest} on the server; nulls are omitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartRequest(String datasetId, String name, String targetUrl, List<String> scorers) {
    }

    /** Anything wrong with the platform's HTTP contract; maps to exit code 2. */
    public static final class PlatformException extends IOException {

        public PlatformException(String message) {
            super(message);
        }

        public PlatformException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
