package io.agentlens.server.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentlens.server.store.SpanRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses OTLP/JSON {@code ExportTraceServiceRequest} payloads into storage records.
 *
 * <p>Follows the protobuf-JSON mapping used by OTLP/HTTP with JSON encoding: camelCase
 * fields, int64 values as decimal strings, trace/span ids as hex strings (base64, as
 * sent by some exporters, is accepted too). AnyValue unions are converted losslessly so
 * {@code gen_ai.*} attributes are stored exactly as emitted. Spans without identifiers
 * or timestamps cannot be stored and are skipped.
 */
@Component
public class OtlpTraceRequestParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final String[] KIND_NAMES = {
            "UNSPECIFIED", "INTERNAL", "SERVER", "CLIENT", "PRODUCER", "CONSUMER"
    };

    public List<SpanRecord> parse(InputStream body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        List<SpanRecord> records = new ArrayList<>();
        for (JsonNode resourceSpans : root.path("resourceSpans")) {
            Map<String, Object> resourceAttrs =
                    attributesToMap(resourceSpans.path("resource").path("attributes"));
            JsonNode resourceJson = MAPPER.valueToTree(resourceAttrs);
            Object serviceNameValue = resourceAttrs.get("service.name");
            String serviceName = serviceNameValue instanceof String name ? name : null;
            for (JsonNode scopeSpans : resourceSpans.path("scopeSpans")) {
                JsonNode scope = scopeSpans.path("scope");
                for (JsonNode span : scopeSpans.path("spans")) {
                    SpanRecord record = toRecord(span, resourceJson, serviceName,
                            textOrNull(scope.path("name")), textOrNull(scope.path("version")));
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        }
        return records;
    }

    private SpanRecord toRecord(JsonNode span, JsonNode resourceJson, String serviceName,
                                String scopeName, String scopeVersion) {
        String traceId = hexId(span.path("traceId"));
        String spanId = hexId(span.path("spanId"));
        if (traceId == null || spanId == null) {
            return null;
        }
        String name = textOrNull(span.path("name"));
        Instant start = instantFromNanos(span.path("startTimeUnixNano"));
        Instant end = instantFromNanos(span.path("endTimeUnixNano"));
        if (name == null || start == null || end == null) {
            return null;
        }
        JsonNode attributes = MAPPER.valueToTree(attributesToMap(span.path("attributes")));
        JsonNode status = span.path("status");
        return new SpanRecord(traceId, spanId, hexId(span.path("parentSpanId")), name,
                kindOf(span.path("kind")), start, end, serviceName, scopeName, scopeVersion,
                statusCodeOf(status), textOrNull(status.path("message")),
                attributes, resourceJson, eventsJson(span.path("events")), linksJson(span.path("links")));
    }

    private static Map<String, Object> attributesToMap(JsonNode attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!attributes.isArray()) {
            return out;
        }
        for (JsonNode kv : attributes) {
            String key = textOrNull(kv.path("key"));
            if (key != null) {
                out.put(key, anyValueToObject(kv.path("value")));
            }
        }
        return out;
    }

    private static Object anyValueToObject(JsonNode value) {
        if (value.has("stringValue")) {
            return value.get("stringValue").asText();
        }
        if (value.has("boolValue")) {
            return value.get("boolValue").asBoolean();
        }
        if (value.has("intValue")) {
            JsonNode n = value.get("intValue");
            if (n.isNumber()) {
                return n.longValue();
            }
            try {
                return Long.parseLong(n.asText().trim());
            } catch (NumberFormatException e) {
                return n.asText();
            }
        }
        if (value.has("doubleValue")) {
            return value.get("doubleValue").asDouble();
        }
        if (value.has("arrayValue")) {
            List<Object> list = new ArrayList<>();
            for (JsonNode v : value.get("arrayValue").path("values")) {
                list.add(anyValueToObject(v));
            }
            return list;
        }
        if (value.has("kvlistValue")) {
            return attributesToMap(value.get("kvlistValue").path("values"));
        }
        if (value.has("bytesValue")) {
            return value.get("bytesValue").asText();
        }
        return null;
    }

    private JsonNode eventsJson(JsonNode events) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode event : events) {
            ObjectNode node = out.addObject();
            node.set("timeUnixNano", event.path("timeUnixNano"));
            node.set("name", event.path("name"));
            node.set("attributes", MAPPER.valueToTree(attributesToMap(event.path("attributes"))));
        }
        return out;
    }

    private JsonNode linksJson(JsonNode links) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode link : links) {
            ObjectNode node = out.addObject();
            node.put("traceId", hexId(link.path("traceId")));
            node.put("spanId", hexId(link.path("spanId")));
            node.set("attributes", MAPPER.valueToTree(attributesToMap(link.path("attributes"))));
        }
        return out;
    }

    /** OTLP/JSON ids are hex; some exporters still send base64 of the raw bytes. */
    private static String hexId(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.length() % 2 == 0 && raw.chars().allMatch(c ->
                Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return raw.toLowerCase(Locale.ROOT);
        }
        try {
            return HexFormat.of().formatHex(Base64.getDecoder().decode(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instantFromNanos(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.isNumber() ? node.bigIntegerValue().toString() : node.asText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            BigInteger[] secAndNano = new BigInteger(raw).divideAndRemainder(NANOS_PER_SECOND);
            return Instant.ofEpochSecond(secAndNano[0].longValue(), secAndNano[1].longValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String kindOf(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return "INTERNAL";
        }
        String raw = node.isNumber() ? node.asText() : node.asText().trim();
        if (raw.startsWith("SPAN_KIND_")) {
            raw = raw.substring("SPAN_KIND_".length());
        }
        try {
            int index = Integer.parseInt(raw);
            return index >= 0 && index < KIND_NAMES.length ? KIND_NAMES[index] : "INTERNAL";
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String statusCodeOf(JsonNode status) {
        JsonNode code = status.path("code");
        if (code.isMissingNode() || code.isNull()) {
            return "UNSET";
        }
        String raw = code.isNumber() ? code.asText() : code.asText().trim();
        if (raw.startsWith("STATUS_CODE_")) {
            raw = raw.substring("STATUS_CODE_".length());
        }
        try {
            int index = Integer.parseInt(raw);
            return switch (index) {
                case 1 -> "OK";
                case 2 -> "ERROR";
                default -> "UNSET";
            };
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }
}
