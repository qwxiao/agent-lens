package io.agentlens.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlens.server.eval.Dataset;
import io.agentlens.server.eval.DatasetCase;
import io.agentlens.server.eval.DatasetSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresDatasetStore implements DatasetStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INSERT_DATASET = """
            INSERT INTO datasets (id, name, description) VALUES (?, ?, ?) RETURNING created_at
            """;

    /**
     * Upsert with "unprovided means keep": the UPDATE keeps existing nullable columns
     * via COALESCE, and only rows the UPDATE missed are INSERTed with defaults filled
     * in. tags/metadata are NOT NULL, so fresh inserts fall back to empty containers.
     */
    private static final String UPDATE_CASE = """
            UPDATE dataset_cases SET
                dataset_id      = ?,
                input           = ?::jsonb,
                expected_output = COALESCE(?, expected_output),
                json_schema     = COALESCE(?::jsonb, json_schema),
                tags            = COALESCE(?::jsonb, tags),
                metadata        = COALESCE(?::jsonb, metadata),
                source_trace_id = COALESCE(?, source_trace_id)
            WHERE id = ?
            """;

    private static final String INSERT_CASE = """
            INSERT INTO dataset_cases (id, dataset_id, input, expected_output, json_schema, tags, metadata, source_trace_id)
            VALUES (?, ?, ?::jsonb, ?, ?::jsonb, COALESCE(?::jsonb, '[]'::jsonb), COALESCE(?::jsonb, '{}'::jsonb), ?)
            """;

    private final JdbcTemplate jdbc;

    public PostgresDatasetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Dataset create(String name, String description) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime createdAt = jdbc.queryForObject(INSERT_DATASET,
                (rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class), id, name, description);
        return new Dataset(id, name, description,
                createdAt == null ? OffsetDateTime.now().toInstant() : createdAt.toInstant());
    }

    @Override
    public List<DatasetSummary> listSummaries() {
        return jdbc.query("""
                SELECT d.id, d.name, d.description, d.created_at, count(c.id) AS case_count
                FROM datasets d LEFT JOIN dataset_cases c ON c.dataset_id = d.id
                GROUP BY d.id, d.name, d.description, d.created_at
                ORDER BY d.created_at DESC
                """, (rs, rowNum) -> new DatasetSummary(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("case_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    @Override
    public java.util.Optional<Dataset> get(String id) {
        List<Dataset> found = jdbc.query("""
                SELECT id, name, description, created_at FROM datasets WHERE id = ?
                """, (rs, rowNum) -> new Dataset(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), id);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    @Override
    public void delete(String id) {
        // eval_runs reference the dataset without ON DELETE; drop its runs first
        // (results cascade with the run), then the dataset (cases cascade too).
        jdbc.update("DELETE FROM eval_runs WHERE dataset_id = ?", id);
        jdbc.update("DELETE FROM datasets WHERE id = ?", id);
    }

    @Override
    public void upsertCases(List<DatasetCase> cases) {
        if (cases.isEmpty()) {
            return;
        }
        int[][] chunks = jdbc.batchUpdate(UPDATE_CASE, cases, 500, (ps, testCase) -> bindCase(ps, testCase, true));
        List<Integer> updatedCounts = new ArrayList<>();
        for (int[] chunk : chunks) {
            for (int count : chunk) {
                updatedCounts.add(count);
            }
        }
        List<DatasetCase> fresh = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (updatedCounts.get(i) == 0) {
                fresh.add(cases.get(i));
            }
        }
        if (!fresh.isEmpty()) {
            jdbc.batchUpdate(INSERT_CASE, fresh, 500, (ps, testCase) -> bindCase(ps, testCase, false));
        }
    }

    /** Binds the shared columns; the UPDATE puts the WHERE id last, the INSERT needs id first. */
    private static void bindCase(java.sql.PreparedStatement ps, DatasetCase testCase, boolean update)
            throws java.sql.SQLException {
        int i = 1;
        if (!update) {
            ps.setString(i++, testCase.id());
        }
        ps.setString(i++, testCase.datasetId());
        ps.setString(i++, testCase.input().toString());
        ps.setString(i++, testCase.expectedOutput());
        ps.setString(i++, jsonOrNull(testCase.jsonSchema()));
        ps.setString(i++, jsonOrNull(testCase.tags()));
        ps.setString(i++, jsonOrNull(testCase.metadata()));
        ps.setString(i++, testCase.sourceTraceId());
        if (update) {
            ps.setString(i, testCase.id());
        }
    }

    @Override
    public List<DatasetCase> listCases(String datasetId) {
        return jdbc.query("""
                SELECT id, dataset_id, input, expected_output, json_schema, tags, metadata, source_trace_id, created_at
                FROM dataset_cases WHERE dataset_id = ? ORDER BY created_at, id
                """, (rs, rowNum) -> new DatasetCase(
                rs.getString("id"),
                rs.getString("dataset_id"),
                jsonNode(rs, "input"),
                rs.getString("expected_output"),
                jsonNode(rs, "json_schema"),
                jsonNode(rs, "tags"),
                jsonNode(rs, "metadata"),
                rs.getString("source_trace_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), datasetId);
    }

    private static String jsonOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static JsonNode jsonNode(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new SQLException("Malformed JSON in column " + column, e);
        }
    }
}
