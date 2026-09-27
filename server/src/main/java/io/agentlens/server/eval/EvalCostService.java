package io.agentlens.server.eval;

import io.agentlens.server.cost.CostCalculator;
import io.agentlens.server.store.SpanRecord;
import io.agentlens.server.store.SpanStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes token usage and cost to eval cases (ADR 0004): the replay response's
 * trace_id is the preferred channel, the {@code agentlens.eval.run_id} /
 * {@code agentlens.eval.case_id} span attributes the fallback; when both point at
 * the same span it is counted once, deduplicated by (trace_id, span_id).
 */
@Service
public class EvalCostService {

    public record CaseCost(long inputTokens, long outputTokens, BigDecimal costUsd, int spanCount) {
    }

    private final SpanStore spans;
    private final CostCalculator calculator;

    public EvalCostService(SpanStore spans, CostCalculator calculator) {
        this.spans = spans;
        this.calculator = calculator;
    }

    /** Case costs keyed by case_id, in the order the results were given. */
    public Map<String, CaseCost> costByCase(String runId, List<RunCaseResult> results) {
        List<String> traceIds = results.stream()
                .map(RunCaseResult::traceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, List<SpanRecord>> byTrace = new HashMap<>();
        if (!traceIds.isEmpty()) {
            spans.findByTraceIds(traceIds).forEach(span ->
                    byTrace.computeIfAbsent(span.traceId(), id -> new ArrayList<>()).add(span));
        }
        Map<String, CaseCost> costs = new LinkedHashMap<>();
        for (RunCaseResult result : results) {
            List<SpanRecord> caseSpans = caseSpans(runId, result, byTrace);
            costs.put(result.caseId(), sum(caseSpans));
        }
        return costs;
    }

    private List<SpanRecord> caseSpans(String runId, RunCaseResult result, Map<String, List<SpanRecord>> byTrace) {
        Map<String, SpanRecord> unique = new LinkedHashMap<>();
        if (result.traceId() != null && !result.traceId().isBlank()) {
            List<SpanRecord> trace = byTrace.get(result.traceId());
            if (trace != null) {
                trace.forEach(span -> unique.put(span.traceId() + "/" + span.spanId(), span));
            }
        }
        spans.findByAttributes(Map.of(
                        "agentlens.eval.run_id", runId,
                        "agentlens.eval.case_id", result.caseId()))
                .forEach(span -> unique.put(span.traceId() + "/" + span.spanId(), span));
        return List.copyOf(unique.values());
    }

    private CaseCost sum(List<SpanRecord> spans) {
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean costKnown = false;
        for (SpanRecord span : spans) {
            CostCalculator.TokenUsage usage = calculator.usageOf(span.attributes());
            if (usage != null) {
                inputTokens += usage.inputTokens();
                outputTokens += usage.outputTokens();
            }
            BigDecimal spanCost = calculator.costOf(span.attributes());
            if (spanCost != null) {
                cost = cost.add(spanCost);
                costKnown = true;
            }
        }
        return new CaseCost(inputTokens, outputTokens, costKnown ? cost : null, spans.size());
    }
}
