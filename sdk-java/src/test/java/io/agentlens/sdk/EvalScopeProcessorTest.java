package io.agentlens.sdk;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvalScopeProcessorTest {

    private final List<SpanData> collected = new CopyOnWriteArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(new EvalScopeProcessor())
            .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter()))
            .build();

    @AfterEach
    void tearDown() {
        EvalScope.clear();
        provider.shutdown();
    }

    private final class CollectingExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            collected.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private SpanData span(String name) {
        Tracer tracer = provider.get("test");
        tracer.spanBuilder(name).startSpan().end();
        return collected.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void stampsRunAndCaseAttributes() {
        EvalScope.set("run-1", "case-2");
        SpanData span = span("tagged");
        assertEquals("run-1", span.getAttributes().get(EvalScope.EVAL_RUN_ID));
        assertEquals("case-2", span.getAttributes().get(EvalScope.EVAL_CASE_ID));
    }

    @Test
    void noScopeMeansNoAttributes() {
        SpanData span = span("untagged");
        assertNull(span.getAttributes().get(EvalScope.EVAL_RUN_ID));
        assertNull(span.getAttributes().get(EvalScope.EVAL_CASE_ID));
    }

    @Test
    void partialScopeTagsOnlyWhatIsSet() {
        EvalScope.set("run-3", null);
        SpanData span = span("half");
        assertEquals("run-3", span.getAttributes().get(EvalScope.EVAL_RUN_ID));
        assertNull(span.getAttributes().get(EvalScope.EVAL_CASE_ID));
    }

    @Test
    void clearStopsTagging() {
        EvalScope.set("run-1", "case-2");
        EvalScope.clear();
        SpanData span = span("after-clear");
        assertNull(span.getAttributes().get(EvalScope.EVAL_RUN_ID));
    }

    @Test
    void blankValuesAreNotStamped() {
        EvalScope.set("  ", "case-2");
        SpanData span = span("blank-run");
        assertNull(span.getAttributes().get(EvalScope.EVAL_RUN_ID));
        assertEquals("case-2", span.getAttributes().get(EvalScope.EVAL_CASE_ID));
    }
}
