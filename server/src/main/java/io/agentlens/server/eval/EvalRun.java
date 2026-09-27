package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One evaluation run against a target agent. {@code scorers} is the JSON array of
 * scorer names applied to each case; {@code status} is running / completed / failed.
 */
public record EvalRun(
        String id,
        String datasetId,
        String name,
        String status,
        String targetUrl,
        JsonNode scorers,
        Instant startedAt,
        Instant finishedAt) {
}
