package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentlens.server.store.DatasetStore;
import io.agentlens.server.store.SpanRecord;
import io.agentlens.server.store.SpanStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Dataset management: NDJSON import/export and snapshotting a stored trace as a
 * case ("fixate from trace"). Trace snapshots read only OTel GenAI semantic
 * convention attributes — {@code gen_ai.input.messages} for the input, the last
 * assistant message of {@code gen_ai.output.messages} as the expected output.
 */
@Service
public class DatasetService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final DatasetStore datasets;
    private final SpanStore spans;

    public DatasetService(DatasetStore datasets, SpanStore spans) {
        this.datasets = datasets;
        this.spans = spans;
    }

    public Dataset create(String name, String description) {
        return datasets.create(name, description);
    }

    public List<DatasetSummary> listSummaries() {
        return datasets.listSummaries();
    }

    public Dataset get(String id) {
        return datasets.get(id).orElseThrow(() -> new NoSuchElementException("dataset not found: " + id));
    }

    public void delete(String id) {
        datasets.delete(id);
    }

    public List<DatasetCase> listCases(String datasetId) {
        get(datasetId);
        return datasets.listCases(datasetId);
    }

    /**
     * Upserts NDJSON lines as cases. Each line needs an {@code input}; without an
     * {@code id} a random one is generated. Returns the number of lines imported.
     */
    public int importJsonl(String datasetId, String ndjson) {
        get(datasetId);
        List<DatasetCase> cases = new ArrayList<>();
        int lineNo = 0;
        for (String line : ndjson.split("\n")) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (IOException e) {
                throw new IllegalArgumentException("line " + lineNo + " is not valid JSON: " + e.getMessage());
            }
            if (node.path("input").isMissingNode() || node.path("input").isNull()) {
                throw new IllegalArgumentException("line " + lineNo + " has no input");
            }
            String id = node.path("id").isTextual() && !node.path("id").asText().isBlank()
                    ? node.path("id").asText()
                    : UUID.randomUUID().toString();
            cases.add(new DatasetCase(id, datasetId, node.path("input"),
                    textOrNull(node, "expected_output"), node.hasNonNull("json_schema") ? node.path("json_schema") : null,
                    node.hasNonNull("tags") ? node.path("tags") : null,
                    node.hasNonNull("metadata") ? node.path("metadata") : null,
                    textOrNull(node, "source_trace_id"), null));
        }
        if (!cases.isEmpty()) {
            datasets.upsertCases(cases);
        }
        return cases.size();
    }

    public String exportJsonl(String datasetId) {
        StringBuilder out = new StringBuilder();
        for (DatasetCase testCase : listCases(datasetId)) {
            out.append(writeValue(testCase)).append('\n');
        }
        return out.toString();
    }

    /** Snapshots a stored trace as an upserted case with the stable id {@code trace-<traceId>}. */
    public DatasetCase caseFromTrace(String datasetId, String traceId) {
        get(datasetId);
        List<SpanRecord> trace = spans.findByTraceIds(List.of(traceId)).stream()
                .sorted(Comparator.comparing(SpanRecord::startTime))
                .toList();
        if (trace.isEmpty()) {
            throw new NoSuchElementException("trace not found: " + traceId);
        }
        JsonNode input = trace.stream()
                .map(span -> messagesOf(span, "gen_ai.input.messages"))
                .filter(messages -> messages != null && messages.isArray() && !messages.isEmpty())
                .findFirst()
                .orElseGet(() -> defaultInput(traceId));
        String expectedOutput = lastAssistantText(trace);
        DatasetCase testCase = new DatasetCase("trace-" + traceId, datasetId, input,
                expectedOutput, null, null, metadataOf(traceId), traceId, null);
        datasets.upsertCases(List.of(testCase));
        return testCase;
    }

    private static JsonNode defaultInput(String traceId) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("trace_id", traceId);
        return input;
    }

    private static JsonNode metadataOf(String traceId) {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("snapshotted_from_trace", traceId);
        return metadata;
    }

    /** The attribute value may be a JSON array or a string containing JSON; both occur in the wild. */
    private static JsonNode messagesOf(SpanRecord span, String attribute) {
        JsonNode value = span.attributes() == null ? null : span.attributes().get(attribute);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            return value;
        }
        if (value.isTextual()) {
            try {
                return MAPPER.readTree(value.asText());
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String lastAssistantText(List<SpanRecord> sortedTrace) {
        for (SpanRecord span : sortedTrace.reversed()) {
            JsonNode messages = messagesOf(span, "gen_ai.output.messages");
            if (messages == null || !messages.isArray()) {
                continue;
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode message = messages.get(i);
                if ("assistant".equalsIgnoreCase(message.path("role").asText())) {
                    String text = contentText(message.path("content"));
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            content.forEach(part -> text.append(part.path("text").asText("")));
            return text.toString();
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static String writeValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize case", e);
        }
    }
}
