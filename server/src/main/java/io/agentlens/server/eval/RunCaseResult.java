package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Per-case outcome of a run, snapshotted at scoring time (ADR 0004): the case input
 * is stored alongside the result so later dataset edits never rewrite history.
 * {@code scores} maps scorer name to {@code {"passed": bool, "detail": string}}.
 */
public record RunCaseResult(
        String runId,
        String caseId,
        JsonNode caseInput,
        String output,
        String error,
        Long latencyMs,
        Boolean passed,
        JsonNode scores,
        String traceId,
        Instant finishedAt) {
}
