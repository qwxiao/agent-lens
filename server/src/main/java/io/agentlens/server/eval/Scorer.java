package io.agentlens.server.eval;

/** Grades one replayed case output. Implementations are stateless and keyed by {@link #name()}. */
public interface Scorer {

    /** Registry key used in the run's scorer list, e.g. {@code "exact_match"}. */
    String name();

    Score score(DatasetCase testCase, String output);

    /**
     * Stored per case as {@code {"passed": bool, "detail": string}}. A null
     * {@code passed} means the scorer skipped the case (it lacks the input the
     * scorer needs, e.g. no json_schema) — skipped scorers do not count towards
     * the case verdict.
     */
    record Score(Boolean passed, String detail) {
    }
}
