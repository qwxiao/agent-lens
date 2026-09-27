package io.agentlens.sdk;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Stamps spans with the {@code agentlens.eval.*} attributes from {@link EvalScope}
 * at start time, so replayed spans carry their run/case attribution without the
 * agent touching span code (ADR 0004).
 */
public final class EvalScopeProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        EvalScope scope = EvalScope.current();
        if (scope == null) {
            return;
        }
        if (scope.runId() != null && !scope.runId().isBlank()) {
            span.setAttribute(EvalScope.EVAL_RUN_ID, scope.runId());
        }
        if (scope.caseId() != null && !scope.caseId().isBlank()) {
            span.setAttribute(EvalScope.EVAL_CASE_ID, scope.caseId());
        }
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // start-only processor
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
