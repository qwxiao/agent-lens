package io.agentlens.server.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** One span as served by the trace detail API, with LLM/tool classification attached. */
public record SpanView(
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        String serviceName,
        Instant startTime,
        long durationMs,
        String category,
        String statusCode,
        String model,
        String system,
        String toolName,
        Long inputTokens,
        Long outputTokens,
        BigDecimal costUsd,
        JsonNode attributes) {
}
