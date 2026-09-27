package io.agentlens.server.eval;

import org.springframework.stereotype.Component;

/** Passes when the output equals the case's expected output, ignoring surrounding whitespace. */
@Component
public class ExactMatchScorer implements Scorer {

    public static final String NAME = "exact_match";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Score score(DatasetCase testCase, String output) {
        if (testCase.expectedOutput() == null) {
            return new Score(null, "skipped: case has no expected_output");
        }
        boolean match = testCase.expectedOutput().trim().equals(output == null ? "" : output.trim());
        return new Score(match, match ? "exact match" : "output differs from expected_output");
    }
}
