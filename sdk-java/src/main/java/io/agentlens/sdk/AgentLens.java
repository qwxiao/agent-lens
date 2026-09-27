package io.agentlens.sdk;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.util.concurrent.TimeUnit;

/**
 * One-line wiring of the standard OpenTelemetry SDK to agent-lens: a tracer provider
 * exporting OTLP/HTTP JSON to the platform, plus the {@link EvalScopeProcessor} that
 * stamps {@code agentlens.eval.*} attributes for cost attribution (ADR 0004/0006).
 * Hand the {@link #openTelemetry()} instance to your framework's OpenTelemetry
 * integration (Spring AI, LangChain4j, plain OTel code) and {@link #close()} on
 * shutdown to flush pending spans.
 */
public final class AgentLens implements AutoCloseable {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final String DEFAULT_SERVICE_NAME = "unknown_service:java";

    private final SdkTracerProvider tracerProvider;
    private final OpenTelemetrySdk openTelemetry;

    private AgentLens(SdkTracerProvider tracerProvider, OpenTelemetrySdk openTelemetry) {
        this.tracerProvider = tracerProvider;
        this.openTelemetry = openTelemetry;
    }

    public static AgentLens create(String endpoint) {
        return create(endpoint, DEFAULT_SERVICE_NAME);
    }

    public static AgentLens create(String endpoint, String serviceName) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(
                        Attributes.of(SERVICE_NAME, serviceName))))
                .addSpanProcessor(new EvalScopeProcessor())
                .addSpanProcessor(BatchSpanProcessor.builder(new OtlpJsonHttpSpanExporter(endpoint)).build())
                .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        return new AgentLens(tracerProvider, openTelemetry);
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    @Override
    public void close() {
        tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
    }
}
