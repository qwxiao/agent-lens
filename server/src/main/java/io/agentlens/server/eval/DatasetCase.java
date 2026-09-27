package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.time.Instant;

/**
 * One replayable case. {@code input} goes to the target agent verbatim; the JSONL
 * file format uses snake_case, matching the SQL columns and the replay protocol
 * from ADR 0004. Null tags/metadata mean "not provided" — upserts keep the
 * previously stored values for those.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetCase(
        String id,
        String datasetId,
        JsonNode input,
        String expectedOutput,
        JsonNode jsonSchema,
        JsonNode tags,
        JsonNode metadata,
        String sourceTraceId,
        Instant createdAt) {
}
