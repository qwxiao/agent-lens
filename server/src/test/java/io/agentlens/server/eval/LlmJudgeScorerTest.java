package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJudgeScorerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer judge;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (judge != null) {
            judge.stop(0);
        }
    }

    @Test
    void passesOnJudgeVerdictPassEvenWhenFenced() throws IOException {
        judgeWith("```json\n{\"verdict\": \"PASS\", \"reason\": \"answers the question\"}\n```");

        Scorer.Score score = scorer().score(testCase(), "Paris");

        assertThat(score.passed()).isTrue();
        assertThat(score.detail()).isEqualTo("answers the question");
        assertThat(lastRequest.get())
                .contains("/chat/completions")
                .contains("\"temperature\":0")
                .contains("Bearer test-key");
    }

    @Test
    void failsOnJudgeVerdictFail() throws IOException {
        judgeWith("{\"verdict\": \"FAIL\", \"reason\": \"wrong city\"}");

        Scorer.Score score = scorer().score(testCase(), "London");

        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).isEqualTo("wrong city");
    }

    @Test
    void failsOnUnparseableJudgeReply() throws IOException {
        judgeWith("I think the answer is fine!");

        Scorer.Score score = scorer().score(testCase(), "Paris");

        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("not a JSON verdict");
    }

    @Test
    void failsWhenJudgeEndpointErrors() throws IOException {
        judgeWith(null, 500);

        Scorer.Score score = scorer().score(testCase(), "Paris");

        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("judge call failed");
    }

    @Test
    void failsWhenNotConfigured() {
        var scorer = new LlmJudgeScorer(new EvalProperties(60000, "", "", ""));

        Scorer.Score score = scorer.score(testCase(), "Paris");

        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("not configured");
    }

    private LlmJudgeScorer scorer() {
        return new LlmJudgeScorer(new EvalProperties(60000,
                "http://localhost:" + judge.getAddress().getPort() + "/v1", "test-key", "judge-model"));
    }

    private void judgeWith(String reply) throws IOException {
        judgeWith(reply, 200);
    }

    private void judgeWith(String reply, int status) throws IOException {
        judge = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        judge.createContext("/v1/chat/completions", exchange -> {
            lastRequest.set(exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " body=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body;
            if (status != 200) {
                body = "boom".getBytes(StandardCharsets.UTF_8);
            } else {
                String content = MAPPER.writeValueAsString(java.util.Map.of(
                        "choices", java.util.List.of(java.util.Map.of(
                                "message", java.util.Map.of("role", "assistant", "content", reply)))));
                body = content.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        judge.start();
    }

    private DatasetCase testCase() {
        return new DatasetCase("c1", "d1", MAPPER.createObjectNode(), "The capital of France",
                null, null, null, null, null);
    }
}
