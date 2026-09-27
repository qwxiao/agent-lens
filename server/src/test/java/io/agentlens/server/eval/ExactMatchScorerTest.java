package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExactMatchScorerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExactMatchScorer scorer = new ExactMatchScorer();

    @Test
    void passesOnTrimmedEquality() {
        var testCase = caseWith("4");
        assertThat(scorer.score(testCase, " 4\n").passed()).isTrue();
    }

    @Test
    void failsOnDifference() {
        var testCase = caseWith("4");
        Scorer.Score score = scorer.score(testCase, "four");
        assertThat(score.passed()).isFalse();
        assertThat(score.detail()).contains("differs");
    }

    @Test
    void skipsCaseWithoutExpectedOutput() {
        var testCase = new DatasetCase("c1", "d1", MAPPER.createObjectNode(), null,
                null, null, null, null, null);
        Scorer.Score score = scorer.score(testCase, "anything");
        assertThat(score.passed()).isNull();
        assertThat(score.detail()).contains("skipped");
    }

    private DatasetCase caseWith(String expected) {
        return new DatasetCase("c1", "d1", MAPPER.createObjectNode(), expected,
                null, null, null, null, null);
    }
}
