package io.agentlens.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpJsonHttpSpanExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BlockingQueue<String> BODIES = new LinkedBlockingQueue<>();
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/traces", exchange -> respond(exchange, 200));
        server.createContext("/fail/v1/traces", exchange -> respond(exchange, 500));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = "{\"partialSuccess\":{}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @AfterEach
    void drain() {
        BODIES.clear();
    }

    private static String nextBody() throws InterruptedException {
        String body = BODIES.poll(5, TimeUnit.SECONDS);
        assertTrue(body != null, "no export arrived within 5s");
        return body;
    }

    private static final class CollectingExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
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

        List<SpanData> collected() {
            return spans;
        }
    }

    @Test
    void encodesSpansAsOtlpJson() throws Exception {
        // batch processor so both spans travel in one export batch
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.BatchSpanProcessor.builder(
                        new OtlpJsonHttpSpanExporter(base)).build())
                .build();
        Tracer tracer = provider.get("test-scope", "1.2.3");

        Span root = tracer.spanBuilder("root").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = root.makeCurrent()) {
            Span child = tracer.spanBuilder("child")
                    .setAttribute("agent", "回答\"引号\"与\n换行\u0001控制符")
                    .setAttribute("tokens", 1234L)
                    .setAttribute("ratio", 0.5)
                    .setAttribute("stream", true)
                    .setAttribute(AttributeKey.stringArrayKey("tags"), List.of("a", "b"))
                    .setAttribute(AttributeKey.longArrayKey("ids"), List.of(1L, 2L))
                    .setAttribute(AttributeKey.doubleArrayKey("scores"), List.of(0.1, 0.9))
                    .setAttribute(AttributeKey.booleanArrayKey("flags"), List.of(true, false))
                    .addLink(root.getSpanContext())
                    .startSpan();
            child.setStatus(StatusCode.ERROR);
            child.addEvent("tool.call", Attributes.of(AttributeKey.stringKey("tool"), "search"),
                    java.time.Instant.now());
            child.end();
        } finally {
            root.end();
        }
        provider.shutdown().join(5, TimeUnit.SECONDS);

        JsonNode document = MAPPER.readTree(nextBody());
        JsonNode resourceSpans = document.path("resourceSpans");
        assertEquals(1, resourceSpans.size());
        assertTrue(resourceSpans.get(0).path("resource").path("attributes").toString()
                .contains("telemetry.sdk"));

        JsonNode scopeSpans = resourceSpans.get(0).path("scopeSpans");
        assertEquals(1, scopeSpans.size());
        assertEquals("test-scope", scopeSpans.get(0).path("scope").path("name").asText());
        assertEquals("1.2.3", scopeSpans.get(0).path("scope").path("version").asText());

        JsonNode spans = scopeSpans.get(0).path("spans");
        assertEquals(2, spans.size());
        JsonNode rootSpan = spans.get(0).path("name").asText().equals("root") ? spans.get(0) : spans.get(1);
        JsonNode childSpan = rootSpan == spans.get(0) ? spans.get(1) : spans.get(0);

        assertEquals("SPAN_KIND_SERVER", rootSpan.path("kind").asText());
        assertEquals("SPAN_KIND_INTERNAL", childSpan.path("kind").asText());
        assertEquals(rootSpan.path("traceId").asText(), childSpan.path("traceId").asText());
        assertEquals(32, rootSpan.path("traceId").asText().length());
        assertEquals(16, rootSpan.path("spanId").asText().length());
        assertEquals(rootSpan.path("spanId").asText(), childSpan.path("parentSpanId").asText());
        assertTrue(childSpan.path("startTimeUnixNano").isTextual());
        assertTrue(childSpan.path("startTimeUnixNano").asText().matches("\\d+"));

        JsonNode attributes = childSpan.path("attributes");
        assertEquals("回答\"引号\"与\n换行\u0001控制符", attr(attributes, "agent").path("stringValue").asText());
        assertEquals("1234", attr(attributes, "tokens").path("intValue").asText());
        assertEquals(0.5, attr(attributes, "ratio").path("doubleValue").asDouble());
        assertTrue(attr(attributes, "stream").path("boolValue").asBoolean());
        assertEquals("a", attr(attributes, "tags").path("arrayValue").path("values").get(0).path("stringValue").asText());
        assertEquals("1", attr(attributes, "ids").path("arrayValue").path("values").get(0).path("intValue").asText());
        assertEquals(0.1, attr(attributes, "scores").path("arrayValue").path("values").get(0).path("doubleValue").asDouble());
        assertTrue(attr(attributes, "flags").path("arrayValue").path("values").get(0).path("boolValue").asBoolean());
        assertFalse(attr(attributes, "flags").path("arrayValue").path("values").get(1).path("boolValue").asBoolean());

        assertEquals("STATUS_CODE_ERROR", childSpan.path("status").path("code").asText());
        assertEquals(rootSpan.path("traceId").asText(), childSpan.path("links").get(0).path("traceId").asText());
        JsonNode toolEvent = eventByName(childSpan, "tool.call");
        assertEquals("search", toolEvent.path("attributes").get(0).path("value").path("stringValue").asText());

        assertFalse(rootSpan.has("attributes"));
    }

    private static JsonNode eventByName(JsonNode span, String name) {
        for (JsonNode event : span.path("events")) {
            if (event.path("name").asText().equals(name)) {
                return event;
            }
        }
        throw new AssertionError("event not found: " + name);
    }

    private static JsonNode attr(JsonNode attributes, String key) {
        for (JsonNode entry : attributes) {
            if (entry.path("key").asText().equals(key)) {
                return entry.path("value");
            }
        }
        throw new AssertionError("attribute not found: " + key);
    }

    @Test
    void serverErrorsAndNetworkFailuresYieldFailureResult() throws IOException {
        CollectingExporter collector = new CollectingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(collector))
                .build();
        provider.get("t").spanBuilder("s").startSpan().end();
        List<SpanData> spans = collector.collected();
        assertEquals(1, spans.size());
        provider.shutdown();

        OtlpJsonHttpSpanExporter failing = new OtlpJsonHttpSpanExporter(base + "/fail");
        assertFalse(failing.export(spans).join(10, TimeUnit.SECONDS).isSuccess());

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OtlpJsonHttpSpanExporter unreachable = new OtlpJsonHttpSpanExporter("http://localhost:" + port);
        assertFalse(unreachable.export(spans).join(10, TimeUnit.SECONDS).isSuccess());
    }
}
