package io.agentlens.server.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Full trace detail: summary fields plus the span list for the waterfall. */
public record TraceDetail(
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
        String rootSpanName,
        List<SpanView> spans) {
}
