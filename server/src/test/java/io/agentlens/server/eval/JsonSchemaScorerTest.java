package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaScorerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonSchemaScorer scorer = new JsonSchemaScorer();

    private static final String SCHEMA = """
            {"type":"object","required":["greeting"],"properties":{"greeting":{"type":"string"}}}
            """;

    @Test
    void passesConformingOutput() {
        Scorer.Score score = scorer.score(caseWithSchema(), "{\"greeting\":\"hi\"}");
        assertThat(score.passed()).isTrue();
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() {
        Scorer.Score score = scorer.score(caseWithSchema(), "```json\n{\"greeting\":\"hi\"}\n```");
        assertThat(score.passed()).isTrue();
    }

    @Test
    void failsOnSchemaViolationWithMessage() {
        Scorer.Score score = scorer.score(caseWithSchema(), "{\"greeting\":42}");
        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("greeting");
    }

    @Test
    void failsOnNonJsonOutput() {
        Scorer.Score score = scorer.score(caseWithSchema(), "plain text answer");
        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("not valid JSON");
    }

    @Test
    void skipsCaseWithoutSchema() {
        var testCase = new DatasetCase("c1", "d1", MAPPER.createObjectNode(), null,
                null, null, null, null, null);
        Scorer.Score score = scorer.score(testCase, "{}");
        assertThat(score.passed()).isNull();
        assertThat(score.detail()).contains("skipped");
    }

    private DatasetCase caseWithSchema() {
        try {
            JsonNode schema = MAPPER.readTree(SCHEMA);
            return new DatasetCase("c1", "d1", MAPPER.createObjectNode(), null,
                    schema, null, null, null, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
