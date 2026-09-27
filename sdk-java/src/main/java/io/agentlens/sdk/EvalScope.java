package io.agentlens.sdk;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Thread-local run/case ids for evaluation replay. When the replayed agent sets
 * {@link #set} while handling a case, {@link EvalScopeProcessor} stamps every span
 * started on that thread with {@code agentlens.eval.run_id} / {@code agentlens.eval.case_id}
 * — the attribute channel the platform uses to attribute cost and latency when no
 * {@code trace_id} comes back in the replay response (ADR 0004). Clear it in a
 * finally block, or accept the whole request's spans being tagged.
 */
public final class EvalScope {

    public static final AttributeKey<String> EVAL_RUN_ID = AttributeKey.stringKey("agentlens.eval.run_id");
    public static final AttributeKey<String> EVAL_CASE_ID = AttributeKey.stringKey("agentlens.eval.case_id");

    private static final ThreadLocal<EvalScope> CURRENT = new ThreadLocal<>();

    private final String runId;
    private final String caseId;

    private EvalScope(String runId, String caseId) {
        this.runId = runId;
        this.caseId = caseId;
    }

    public static void set(String runId, String caseId) {
        CURRENT.set(new EvalScope(runId, caseId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    static EvalScope current() {
        return CURRENT.get();
    }

    String runId() {
        return runId;
    }

    String caseId() {
        return caseId;
    }
}
