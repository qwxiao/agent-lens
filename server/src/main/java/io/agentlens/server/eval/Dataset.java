package io.agentlens.server.eval;

import java.time.Instant;

/** An evaluation dataset: a named, versionable set of replayable cases. */
public record Dataset(String id, String name, String description, Instant createdAt) {
}
