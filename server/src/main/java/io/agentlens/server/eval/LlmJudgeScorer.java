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
import java.util.List;
import java.util.Map;

/**
 * LLM-as-judge over any OpenAI-compatible {@code /chat/completions} endpoint
 * (ADR 0004): no LLM SDK on the classpath, just {@code JUDGE_BASE_URL} /
 * {@code JUDGE_API_KEY} / {@code JUDGE_MODEL}. The judge is instructed to reply
 * with a JSON verdict {@code {"verdict": "PASS"|"FAIL", "reason": "..."}}.
 */
@Component
public class LlmJudgeScorer implements Scorer {

    public static final String NAME = "llm_judge";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvalProperties props;
    private final HttpClient http;

    public LlmJudgeScorer(EvalProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Score score(DatasetCase testCase, String output) {
        if (!props.judgeConfigured()) {
            return new Score(false, "llm_judge not configured (set JUDGE_BASE_URL and JUDGE_MODEL)");
        }
        JsonNode reply;
        try {
            reply = complete(prompt(testCase, output));
        } catch (IOException e) {
            return new Score(false, "judge call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Score(false, "judge call interrupted");
        }
        return verdictOf(reply);
    }

    private Score verdictOf(JsonNode reply) {
        JsonNode content = reply.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            return new Score(false, "judge reply has no message content");
        }
        try {
            JsonNode verdict = MAPPER.readTree(FencedJson.strip(content.asText()));
            String reason = verdict.path("reason").asText("");
            if ("PASS".equalsIgnoreCase(verdict.path("verdict").asText())) {
                return new Score(true, reason.isBlank() ? "judge passed" : reason);
            }
            return new Score(false, reason.isBlank() ? "judge failed without a reason" : reason);
        } catch (IOException e) {
            return new Score(false, "judge reply is not a JSON verdict: " + content.asText());
        }
    }

    private JsonNode complete(String prompt) throws IOException, InterruptedException {
        String base = props.judgeBaseUrl().endsWith("/")
                ? props.judgeBaseUrl().substring(0, props.judgeBaseUrl().length() - 1)
                : props.judgeBaseUrl();
        String body = MAPPER.writeValueAsString(Map.of(
                "model", props.judgeModel(),
                "temperature", 0,
                "messages", List.of(Map.of("role", "user", "content", prompt))));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.replayTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!props.judgeApiKey().isBlank()) {
            request.header("Authorization", "Bearer " + props.judgeApiKey());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("judge endpoint returned HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private static String prompt(DatasetCase testCase, String output) {
        String expected = testCase.expectedOutput() == null
                ? "(not provided — judge the answer on its own merits)"
                : testCase.expectedOutput();
        return """
                You are grading an AI agent's answer for an evaluation test case. \
                Judge whether the actual output answers the input correctly.\
                
                INPUT: %s\
                
                EXPECTED OUTPUT: %s\
                
                ACTUAL OUTPUT: %s\
                
                Reply with ONLY a JSON object: {"verdict": "PASS" or "FAIL", "reason": "<short explanation>"}"""
                .formatted(testCase.input(), expected, output == null ? "(no output)" : output);
    }
}
