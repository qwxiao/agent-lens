package io.agentlens.cli.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * The slice of {@code GET /api/eval-runs/{id}} the CLI reads. Unknown fields are kept
 * so newer platforms stay compatible with older CLIs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunDetail(Run run, Totals totals, List<CaseView> cases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Run(String id, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(long caseCount, long passedCount, double passRate, Long avgLatencyMs,
                         Long inputTokens, Long outputTokens, BigDecimal costUsd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseView(String caseId, Boolean passed, String error, JsonNode scores) {
    }
}
