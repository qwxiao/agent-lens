package io.agentlens.cli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCommandTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final EvalCommand command = new EvalCommand();

    private static RunDetail settled(RunDetail.Totals totals, List<RunDetail.CaseView> cases) {
        return new RunDetail(new RunDetail.Run("run-1", "completed"), totals, cases);
    }

    private static RunDetail.Totals totals(long caseCount, long passed, double passRate, BigDecimal cost) {
        return new RunDetail.Totals(caseCount, passed, passRate, 120L, 1000L, 100L, cost);
    }

    private EvalArgs args(Double gate) throws Exception {
        if (gate == null) {
            return EvalArgs.parse(new String[]{"--dataset", "ds1", "--target", "http://agent/replay"}, null);
        }
        return EvalArgs.parse(new String[]{"--dataset", "ds1", "--target", "http://agent/replay",
                "--scorers", "exact_match", "--gate", gate.toString()}, null);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void gateBelowThresholdExitsOne() throws Exception {
        int code = command.report(settled(totals(10, 8, 0.8, null), List.of()), args(0.9), out, err);
        assertEquals(1, code);
        assertTrue(out().contains("8/10 cases passed (80.0%)"));
        assertTrue(err().contains("gate 90.0% not met"));
    }

    @Test
    void gateExactlyAtThresholdPasses() throws Exception {
        int code = command.report(settled(totals(10, 9, 0.9, null), List.of()), args(0.9), out, err);
        assertEquals(0, code);
        assertTrue(out().contains("gate 90.0% met"));
    }

    @Test
    void withoutGateACompletedRunAlwaysPasses() throws Exception {
        int code = command.report(settled(totals(10, 4, 0.4, null), List.of()), args(null), out, err);
        assertEquals(0, code);
        assertFalse(out().contains("gate"));
    }

    @Test
    void failedRunIsAnOperationalErrorNotAGateFailure() throws Exception {
        RunDetail detail = new RunDetail(new RunDetail.Run("run-1", "failed"), null, List.of());
        int code = command.report(detail, args(0.9), out, err);
        assertEquals(2, code);
        assertTrue(err().contains("failed"));
    }

    @Test
    void unexpectedStatusIsAnOperationalError() throws Exception {
        RunDetail detail = new RunDetail(new RunDetail.Run("run-1", "mystery"), null, List.of());
        assertEquals(2, command.report(detail, args(null), out, err));
    }

    @Test
    void emptyDatasetIsAnOperationalError() throws Exception {
        int code = command.report(settled(totals(0, 0, 0.0, null), List.of()), args(0.9), out, err);
        assertEquals(2, code);
        assertTrue(err().contains("no cases"));
    }

    @Test
    void missingTotalsIsAnOperationalError() throws Exception {
        int code = command.report(settled(null, List.of()), args(null), out, err);
        assertEquals(2, code);
    }

    @Test
    void failuresAreListedWithScorerDetailsAndCaseErrors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scores = mapper.createObjectNode();
        scores.set("exact_match", mapper.createObjectNode()
                .put("passed", false)
                .put("detail", "expected \"five\", got \"4\""));
        List<RunDetail.CaseView> cases = List.of(
                new RunDetail.CaseView("c1", false, "connection refused", null),
                new RunDetail.CaseView("c2", false, null, scores));
        int code = command.report(settled(totals(2, 0, 0.0, null), cases), args(0.9), out, err);
        assertEquals(1, code);
        assertTrue(out().contains("failed c1: connection refused"));
        assertTrue(out().contains("failed c2: exact_match: expected \"five\", got \"4\""));
    }

    @Test
    void costIsPrintedOnlyWhenKnown() throws Exception {
        command.report(settled(totals(2, 2, 1.0, new BigDecimal("0.0134")), List.of()), args(null), out, err);
        assertTrue(out().contains("cost $0.0134"));

        outBytes.reset();
        command.report(settled(totals(2, 2, 1.0, null), List.of()), args(null), out, err);
        assertFalse(out().contains("cost $"));
    }

    @Test
    void summaryOmitsLatencyWhenUnknown() throws Exception {
        RunDetail.Totals noLatency = new RunDetail.Totals(2, 2, 1.0, null, null, null, null);
        command.report(settled(noLatency, List.of()), args(null), out, err);
        assertTrue(out().contains("avg latency n/a"));
    }
}
