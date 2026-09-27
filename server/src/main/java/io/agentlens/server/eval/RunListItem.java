package io.agentlens.server.eval;

import java.time.Instant;

/** Run list entry with case counts aggregated in the store query. */
public record RunListItem(
        String id,
        String datasetId,
        String name,
        String status,
        String targetUrl,
        Instant startedAt,
        Instant finishedAt,
        long caseCount,
        long passedCount) {
}
