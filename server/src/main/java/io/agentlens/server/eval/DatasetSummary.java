package io.agentlens.server.eval;

import java.time.Instant;

/** Dataset list entry with its case count, so the dashboard needs no per-dataset query. */
public record DatasetSummary(String id, String name, String description, long caseCount, Instant createdAt) {
}
