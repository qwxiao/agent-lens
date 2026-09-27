package io.agentlens.server.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One normalized OTLP span. Attribute/event trees keep the wire values verbatim, so
 * semantic conventions such as {@code gen_ai.*} survive ingestion unchanged.
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String spanKind,
        Instant startTime,
        Instant endTime,
        String serviceName,
        String scopeName,
        String scopeVersion,
        String statusCode,
        String statusMessage,
        JsonNode attributes,
        JsonNode resourceAttributes,
        JsonNode events,
        JsonNode links) {
}
