package io.agentlens.server.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Aggregated view of one trace, as served by the query API. */
public record TraceSummary(
        String traceId,
        Instant startTime,
        Instant endTime,
        long durationMs,
        int spanCount,
        List<String> services,
        List<String> models,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        String rootSpanName) {
}
