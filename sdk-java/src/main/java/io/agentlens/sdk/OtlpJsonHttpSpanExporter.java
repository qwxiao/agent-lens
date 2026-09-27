package io.agentlens.sdk;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ships finished spans to agent-lens over OTLP/HTTP with JSON encoding — the one
 * encoding the platform's {@code POST /v1/traces} speaks. Hand-rolled JSON keeps the
 * SDK free of protobuf machinery (ADR 0006); the wire format stays exactly OTLP, no
 * protocol extensions.
 */
public final class OtlpJsonHttpSpanExporter implements SpanExporter {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String TRACES_PATH = "/v1/traces";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI tracesUri;

    /** {@code endpoint} is the agent-lens base URL, with or without the trailing {@code /v1/traces}. */
    public OtlpJsonHttpSpanExporter(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.tracesUri = URI.create(base.endsWith(TRACES_PATH) ? base : base + TRACES_PATH);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        HttpRequest request = HttpRequest.newBuilder(tracesUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(spans)))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2
                    ? CompletableResultCode.ofSuccess()
                    : CompletableResultCode.ofFailure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableResultCode.ofFailure();
        } catch (java.io.IOException e) {
            return CompletableResultCode.ofFailure();
        }
    }

    /** One export batch: resource → scope → spans, exactly the OTLP/JSON shape. */
    String encode(Collection<SpanData> spans) {
        Map<Resource, Map<InstrumentationScopeInfo, List<SpanData>>> byResource = new LinkedHashMap<>();
        for (SpanData span : spans) {
            byResource.computeIfAbsent(span.getResource(), r -> new LinkedHashMap<>())
                    .computeIfAbsent(span.getInstrumentationScopeInfo(), s -> new java.util.ArrayList<>())
                    .add(span);
        }
        StringBuilder json = new StringBuilder(2048).append("{\"resourceSpans\":[");
        boolean firstResource = true;
        for (Map.Entry<Resource, Map<InstrumentationScopeInfo, List<SpanData>>> resource : byResource.entrySet()) {
            if (!firstResource) {
                json.append(',');
            }
            firstResource = false;
            json.append("{\"resource\":");
            writeResource(json, resource.getKey());
            json.append(",\"scopeSpans\":[");
            boolean firstScope = true;
            for (Map.Entry<InstrumentationScopeInfo, List<SpanData>> scope : resource.getValue().entrySet()) {
                if (!firstScope) {
                    json.append(',');
                }
                firstScope = false;
                json.append("{\"scope\":");
                writeScope(json, scope.getKey());
                json.append(",\"spans\":[");
                boolean firstSpan = true;
                for (SpanData span : scope.getValue()) {
                    if (!firstSpan) {
                        json.append(',');
                    }
                    firstSpan = false;
                    writeSpan(json, span);
                }
                json.append("]}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static void writeResource(StringBuilder json, Resource resource) {
        json.append("{\"attributes\":");
        writeAttributes(json, resource.getAttributes());
        json.append('}');
    }

    private static void writeScope(StringBuilder json, InstrumentationScopeInfo scope) {
        json.append("{\"name\":").append(quote(scope.getName()));
        String version = scope.getVersion();
        if (version != null && !version.isEmpty()) {
            json.append(",\"version\":").append(quote(version));
        }
        json.append('}');
    }

    private static void writeSpan(StringBuilder json, SpanData span) {
        json.append("{\"traceId\":").append(quote(span.getTraceId()))
                .append(",\"spanId\":").append(quote(span.getSpanId()));
        String parentSpanId = span.getParentSpanId();
        if (!parentSpanId.isEmpty() && !parentSpanId.equals("0000000000000000")) {
            json.append(",\"parentSpanId\":").append(quote(parentSpanId));
        }
        json.append(",\"name\":").append(quote(span.getName()))
                .append(",\"kind\":\"SPAN_KIND_").append(span.getKind().name()).append('"')
                .append(",\"startTimeUnixNano\":\"").append(span.getStartEpochNanos()).append('"')
                .append(",\"endTimeUnixNano\":\"").append(span.getEndEpochNanos()).append('"');
        Attributes attributes = span.getAttributes();
        if (!attributes.isEmpty()) {
            json.append(",\"attributes\":");
            writeAttributes(json, attributes);
        }
        if (!span.getEvents().isEmpty()) {
            json.append(",\"events\":[");
            boolean first = true;
            for (EventData event : span.getEvents()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"timeUnixNano\":\"").append(event.getEpochNanos())
                        .append("\",\"name\":").append(quote(event.getName()));
                if (!event.getAttributes().isEmpty()) {
                    json.append(",\"attributes\":");
                    writeAttributes(json, event.getAttributes());
                }
                json.append('}');
            }
            json.append(']');
        }
        if (!span.getLinks().isEmpty()) {
            json.append(",\"links\":[");
            boolean first = true;
            for (LinkData link : span.getLinks()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"traceId\":").append(quote(link.getSpanContext().getTraceId()))
                        .append(",\"spanId\":").append(quote(link.getSpanContext().getSpanId()));
                if (!link.getAttributes().isEmpty()) {
                    json.append(",\"attributes\":");
                    writeAttributes(json, link.getAttributes());
                }
                json.append('}');
            }
            json.append(']');
        }
        if (span.getStatus().getStatusCode() != io.opentelemetry.api.trace.StatusCode.UNSET) {
            json.append(",\"status\":{\"code\":\"STATUS_CODE_")
                    .append(span.getStatus().getStatusCode().name()).append('"');
            if (span.getStatus().getDescription() != null && !span.getStatus().getDescription().isEmpty()) {
                json.append(",\"message\":").append(quote(span.getStatus().getDescription()));
            }
            json.append('}');
        }
        json.append('}');
    }

    private static void writeAttributes(StringBuilder json, Attributes attributes) {
        json.append('[');
        boolean first = true;
        for (Map.Entry<AttributeKey<?>, Object> attribute : attributes.asMap().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"key\":").append(quote(attribute.getKey().getKey())).append(",\"value\":");
            writeValue(json, attribute.getKey().getType(), attribute.getValue());
            json.append('}');
        }
        json.append(']');
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder json, AttributeType type, Object value) {
        // OTLP JSON encodes 64-bit ints as strings (proto3 JSON mapping), which is
        // what the platform parser expects
        switch (type) {
            case STRING -> json.append("{\"stringValue\":").append(quote((String) value)).append('}');
            case LONG -> json.append("{\"intValue\":\"").append(value).append("\"}");
            case DOUBLE -> json.append("{\"doubleValue\":").append(value).append('}');
            case BOOLEAN -> json.append("{\"boolValue\":").append(value).append('}');
            case STRING_ARRAY, LONG_ARRAY, BOOLEAN_ARRAY, DOUBLE_ARRAY -> {
                json.append("{\"arrayValue\":{\"values\":[");
                boolean first = true;
                for (Object element : (List<Object>) value) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    writeValue(json, elementType(type), element);
                }
                json.append("]}}");
            }
            default -> json.append("{\"stringValue\":").append(quote(String.valueOf(value))).append('}');
        }
    }

    private static AttributeType elementType(AttributeType arrayType) {
        return switch (arrayType) {
            case STRING_ARRAY -> AttributeType.STRING;
            case LONG_ARRAY -> AttributeType.LONG;
            case BOOLEAN_ARRAY -> AttributeType.BOOLEAN;
            case DOUBLE_ARRAY -> AttributeType.DOUBLE;
            default -> AttributeType.STRING;
        };
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        http.close();
        return CompletableResultCode.ofSuccess();
    }
}
