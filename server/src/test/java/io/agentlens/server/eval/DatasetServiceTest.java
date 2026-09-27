package io.agentlens.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlens.server.store.DatasetStore;
import io.agentlens.server.store.SpanRecord;
import io.agentlens.server.store.SpanStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InMemoryDatasetStore store = new InMemoryDatasetStore();
    private final List<SpanRecord> storedSpans = new ArrayList<>();
    private final DatasetService service = new DatasetService(store, new StaticSpanStore(storedSpans));

    @Test
    void importsNdjsonLinesWithExplicitAndGeneratedIds() {
        String datasetId = service.create("math", null).id();
        String ndjson = """
                {"id":"c1","input":{"question":"2+2"},"expected_output":"4"}
                {"input":{"question":"2+3"},"expected_output":"5","tags":["easy"],"metadata":{"sheet":"a"}}
                """;

        int imported = service.importJsonl(datasetId, ndjson);

        assertThat(imported).isEqualTo(2);
        List<DatasetCase> cases = service.listCases(datasetId);
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).id()).isEqualTo("c1");
        assertThat(cases.get(1).id()).isNotBlank().isNotEqualTo("c1");
        assertThat(cases.get(1).tags().get(0).asText()).isEqualTo("easy");
        assertThat(cases.get(1).metadata().path("sheet").asText()).isEqualTo("a");
        assertThat(cases.get(0).datasetId()).isEqualTo(datasetId);
    }

    @Test
    void rejectsLinesWithoutInput() {
        String datasetId = service.create("math", null).id();

        assertThatThrownBy(() -> service.importJsonl(datasetId, "{\"expected_output\":\"4\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }

    @Test
    void upsertByStableIdKeepsOneRow() {
        String datasetId = service.create("math", null).id();
        service.importJsonl(datasetId, "{\"id\":\"c1\",\"input\":{\"q\":\"2+2\"},\"expected_output\":\"4\"}");
        service.importJsonl(datasetId, "{\"id\":\"c1\",\"input\":{\"q\":\"2+2\"},\"expected_output\":\"5\"}");

        List<DatasetCase> cases = service.listCases(datasetId);
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).expectedOutput()).isEqualTo("5");
    }

    @Test
    void exportsSnakeCaseNdjson() throws IOException {
        String datasetId = service.create("math", null).id();
        service.importJsonl(datasetId, "{\"id\":\"c1\",\"input\":{\"q\":\"2+2\"},\"expected_output\":\"4\","
                + "\"json_schema\":{\"type\":\"string\"},\"source_trace_id\":\"abc\"}");

        String exported = service.exportJsonl(datasetId);
        JsonNode line = MAPPER.readTree(exported.trim());

        assertThat(line.path("id").asText()).isEqualTo("c1");
        assertThat(line.path("expected_output").asText()).isEqualTo("4");
        assertThat(line.path("json_schema").path("type").asText()).isEqualTo("string");
        assertThat(line.path("source_trace_id").asText()).isEqualTo("abc");
        assertThat(line.path("input").path("q").asText()).isEqualTo("2+2");
    }

    @Test
    void snapshotsTraceMessagesIntoCase() throws IOException {
        String datasetId = service.create("prod", null).id();
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        storedSpans.add(span(traceId, "s1", null, """
                {"gen_ai.input.messages":"[{\\"role\\":\\"user\\",\\"content\\":\\"what is 2+2?\\"}]"}
                """));
        storedSpans.add(span(traceId, "s2", "s1", """
                {"gen_ai.output.messages":[{"role":"assistant","content":"4"}]}
                """));

        DatasetCase testCase = service.caseFromTrace(datasetId, traceId);

        assertThat(testCase.id()).isEqualTo("trace-" + traceId);
        assertThat(testCase.sourceTraceId()).isEqualTo(traceId);
        assertThat(testCase.input().isArray()).isTrue();
        assertThat(testCase.input().get(0).path("content").asText()).isEqualTo("what is 2+2?");
        assertThat(testCase.expectedOutput()).isEqualTo("4");
    }

    @Test
    void snapshotWithoutMessagesFallsBackToTraceReference() {
        String datasetId = service.create("prod", null).id();
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        storedSpans.add(span(traceId, "s1", null, "{}"));

        DatasetCase testCase = service.caseFromTrace(datasetId, traceId);

        assertThat(testCase.input().path("trace_id").asText()).isEqualTo(traceId);
        assertThat(testCase.expectedOutput()).isNull();
    }

    @Test
    void snapshotOfUnknownTraceFails() {
        String datasetId = service.create("prod", null).id();

        assertThatThrownBy(() -> service.caseFromTrace(datasetId, "missing"))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static SpanRecord span(String traceId, String spanId, String parentSpanId, String attributes) {
        try {
            return new SpanRecord(traceId, spanId, parentSpanId, "agent.run", "SPAN_KIND_SERVER",
                    Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-27T10:00:01Z"),
                    "demo-agent", "scope", "1.0", "STATUS_CODE_OK", null,
                    MAPPER.readTree(attributes), MAPPER.readTree("{}"), MAPPER.readTree("[]"), MAPPER.readTree("[]"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class InMemoryDatasetStore implements DatasetStore {
        private final Map<String, Dataset> datasets = new LinkedHashMap<>();
        private final Map<String, DatasetCase> cases = new LinkedHashMap<>();

        @Override
        public Dataset create(String name, String description) {
            Dataset dataset = new Dataset(UUID.randomUUID().toString(), name, description, Instant.now());
            datasets.put(dataset.id(), dataset);
            return dataset;
        }

        @Override
        public List<DatasetSummary> listSummaries() {
            return datasets.values().stream()
                    .map(dataset -> new DatasetSummary(dataset.id(), dataset.name(), dataset.description(),
                            cases.values().stream().filter(c -> c.datasetId().equals(dataset.id())).count(),
                            dataset.createdAt()))
                    .toList();
        }

        @Override
        public Optional<Dataset> get(String id) {
            return Optional.ofNullable(datasets.get(id));
        }

        @Override
        public void delete(String id) {
            datasets.remove(id);
            cases.values().removeIf(testCase -> testCase.datasetId().equals(id));
        }

        @Override
        public void upsertCases(List<DatasetCase> upserts) {
            upserts.forEach(testCase -> cases.put(testCase.id(),
                    new DatasetCase(testCase.id(), testCase.datasetId(), testCase.input(),
                            testCase.expectedOutput(), testCase.jsonSchema(), testCase.tags(),
                            testCase.metadata(), testCase.sourceTraceId(),
                            testCase.createdAt() == null ? Instant.now() : testCase.createdAt())));
        }

        @Override
        public List<DatasetCase> listCases(String datasetId) {
            return cases.values().stream()
                    .filter(testCase -> testCase.datasetId().equals(datasetId))
                    .toList();
        }
    }

    private record StaticSpanStore(List<SpanRecord> spans) implements SpanStore {

        @Override
        public int insert(Collection<SpanRecord> batch) {
            return 0;
        }

        @Override
        public List<String> recentTraceIds(int limit, int offset) {
            return List.of();
        }

        @Override
        public long countTraces() {
            return 0;
        }

        @Override
        public List<SpanRecord> findByTraceIds(Collection<String> traceIds) {
            return spans.stream().filter(span -> traceIds.contains(span.traceId())).toList();
        }

        @Override
        public List<SpanRecord> findByAttributes(Map<String, String> attributeEquals) {
            return List.of();
        }
    }
}
