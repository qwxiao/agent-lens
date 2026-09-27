package io.agentlens.server.store;

import java.util.Collection;
import java.util.List;

/**
 * Persistence boundary for spans. Isolating storage behind this interface keeps the
 * door open for a column-oriented backend when PostgreSQL stops being enough (ADR 0002).
 */
public interface SpanStore {

    /** @return number of rows actually inserted; duplicate spans are ignored */
    int insert(Collection<SpanRecord> spans);

    /** Trace ids ordered by most recent span activity. */
    List<String> recentTraceIds(int limit, int offset);

    long countTraces();

    List<SpanRecord> findByTraceIds(Collection<String> traceIds);
}
