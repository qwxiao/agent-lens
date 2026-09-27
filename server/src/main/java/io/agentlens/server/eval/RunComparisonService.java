package io.agentlens.server.eval;

import io.agentlens.server.eval.EvalCostService.CaseCost;
import io.agentlens.server.store.RunStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Assembles per-run detail (results decorated with attributed cost) and the
 * side-by-side comparison of two runs, joined on the stable case ids — this is
 * what makes "changed the prompt, re-ran" legible (PLAN.md M2).
 */
@Service
public class RunComparisonService {

    public record RunTotals(long caseCount, long passedCount, double passRate, Long avgLatencyMs,
                            long inputTokens, long outputTokens, BigDecimal costUsd) {
    }

    public record RunCaseView(String caseId, String output, String error, Long latencyMs, Boolean passed,
                              com.fasterxml.jackson.databind.JsonNode scores, String traceId,
                              CaseCost cost) {
    }

    public record RunDetail(EvalRun run, RunTotals totals, List<RunCaseView> cases) {
    }

    /** One case across both runs; fields are null when the case is absent from that run. */
    public record CaseDiff(String caseId, Boolean passedA, Boolean passedB,
                           Long latencyMsA, Long latencyMsB, BigDecimal costA, BigDecimal costB,
                           String errorA, String errorB) {
    }

    public record RunComparison(RunDetail runA, RunDetail runB, List<CaseDiff> cases) {
    }

    private final RunStore runs;
    private final EvalCostService costs;

    public RunComparisonService(RunStore runs, EvalCostService costs) {
        this.runs = runs;
        this.costs = costs;
    }

    public RunDetail detail(String runId) {
        EvalRun run = runs.get(runId).orElseThrow(() -> new NoSuchElementException("run not found: " + runId));
        List<RunCaseResult> results = runs.results(runId);
        Map<String, CaseCost> costByCase = costs.costByCase(runId, results);
        List<RunCaseView> views = results.stream()
                .map(result -> new RunCaseView(result.caseId(), result.output(), result.error(),
                        result.latencyMs(), result.passed(), result.scores(), result.traceId(),
                        costByCase.get(result.caseId())))
                .toList();
        return new RunDetail(run, totalsOf(results, costByCase), views);
    }

    public RunComparison compare(String runIdA, String runIdB) {
        RunDetail a = detail(runIdA);
        RunDetail b = detail(runIdB);
        Map<String, RunCaseView> byCaseA = new LinkedHashMap<>();
        a.cases().forEach(view -> byCaseA.put(view.caseId(), view));
        Map<String, RunCaseView> byCaseB = new LinkedHashMap<>();
        b.cases().forEach(view -> byCaseB.put(view.caseId(), view));
        List<CaseDiff> diffs = new ArrayList<>();
        byCaseA.forEach((caseId, viewA) -> {
            RunCaseView viewB = byCaseB.remove(caseId);
            diffs.add(diff(caseId, viewA, viewB));
        });
        byCaseB.forEach((caseId, viewB) -> diffs.add(diff(caseId, null, viewB)));
        return new RunComparison(a, b, List.copyOf(diffs));
    }

    private static CaseDiff diff(String caseId, RunCaseView a, RunCaseView b) {
        return new CaseDiff(caseId,
                a == null ? null : a.passed(),
                b == null ? null : b.passed(),
                a == null ? null : a.latencyMs(),
                b == null ? null : b.latencyMs(),
                a == null || a.cost() == null ? null : a.cost().costUsd(),
                b == null || b.cost() == null ? null : b.cost().costUsd(),
                a == null ? null : a.error(),
                b == null ? null : b.error());
    }

    private static RunTotals totalsOf(List<RunCaseResult> results, Map<String, CaseCost> costByCase) {
        long passed = results.stream().filter(result -> Boolean.TRUE.equals(result.passed())).count();
        long latencySum = 0;
        long latencyCount = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costKnown = false;
        for (RunCaseResult result : results) {
            if (result.latencyMs() != null) {
                latencySum += result.latencyMs();
                latencyCount++;
            }
            CaseCost caseCost = costByCase.get(result.caseId());
            if (caseCost != null) {
                inputTokens += caseCost.inputTokens();
                outputTokens += caseCost.outputTokens();
                if (caseCost.costUsd() != null) {
                    cost = cost.add(caseCost.costUsd());
                    costKnown = true;
                }
            }
        }
        return new RunTotals(results.size(), passed,
                results.isEmpty() ? 0 : (double) passed / results.size(),
                latencyCount == 0 ? null : latencySum / latencyCount,
                inputTokens, outputTokens, costKnown ? cost : null);
    }
}
