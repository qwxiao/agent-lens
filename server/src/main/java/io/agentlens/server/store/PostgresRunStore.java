package io.agentlens.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlens.server.eval.EvalRun;
import io.agentlens.server.eval.RunCaseResult;
import io.agentlens.server.eval.RunListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class PostgresRunStore implements RunStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INSERT_RUN = """
            INSERT INTO eval_runs (id, dataset_id, name, status, target_url, scorers, started_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final String INSERT_RESULT = """
            INSERT INTO eval_run_cases (run_id, case_id, case_input, output, error, latency_ms, passed, scores, trace_id, finished_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public PostgresRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(EvalRun run) {
        jdbc.update(INSERT_RUN,
                run.id(),
                run.datasetId(),
                run.name(),
                run.status(),
                run.targetUrl(),
                run.scorers().toString(),
                OffsetDateTime.ofInstant(run.startedAt(), ZoneOffset.UTC));
    }

    @Override
    public Optional<EvalRun> get(String id) {
        List<EvalRun> found = jdbc.query("""
                SELECT id, dataset_id, name, status, target_url, scorers, started_at, finished_at
                FROM eval_runs WHERE id = ?
                """, (rs, rowNum) -> new EvalRun(
                rs.getString("id"),
                rs.getString("dataset_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("target_url"),
                jsonNode(rs, "scorers"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                instantOrNull(rs, "finished_at")), id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<RunListItem> listSummaries(int limit) {
        return jdbc.query("""
                SELECT r.id, r.dataset_id, r.name, r.status, r.target_url, r.started_at, r.finished_at,
                       count(c.id) AS case_count,
                       count(c.id) FILTER (WHERE c.passed) AS passed_count
                FROM eval_runs r LEFT JOIN eval_run_cases c ON c.run_id = r.id
                GROUP BY r.id, r.dataset_id, r.name, r.status, r.target_url, r.started_at, r.finished_at
                ORDER BY r.started_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new RunListItem(
                rs.getString("id"),
                rs.getString("dataset_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("target_url"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                instantOrNull(rs, "finished_at"),
                rs.getLong("case_count"),
                rs.getLong("passed_count")), limit);
    }

    @Override
    public void updateStatus(String runId, String status, Instant finishedAt) {
        jdbc.update("UPDATE eval_runs SET status = ?, finished_at = ? WHERE id = ?",
                status, finishedAt == null ? null : OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC), runId);
    }

    @Override
    public void insertResult(RunCaseResult result) {
        jdbc.update(INSERT_RESULT,
                result.runId(),
                result.caseId(),
                result.caseInput() == null ? null : result.caseInput().toString(),
                result.output(),
                result.error(),
                result.latencyMs(),
                result.passed(),
                result.scores() == null ? "{}" : result.scores().toString(),
                result.traceId(),
                OffsetDateTime.ofInstant(result.finishedAt(), ZoneOffset.UTC));
    }

    @Override
    public List<RunCaseResult> results(String runId) {
        return jdbc.query("""
                SELECT run_id, case_id, case_input, output, error, latency_ms, passed, scores, trace_id, finished_at
                FROM eval_run_cases WHERE run_id = ? ORDER BY id
                """, (rs, rowNum) -> {
            long latency = rs.getLong("latency_ms");
            boolean latencyNull = rs.wasNull();
            return new RunCaseResult(
                    rs.getString("run_id"),
                    rs.getString("case_id"),
                    jsonNode(rs, "case_input"),
                    rs.getString("output"),
                    rs.getString("error"),
                    latencyNull ? null : latency,
                    (Boolean) rs.getObject("passed"),
                    jsonNode(rs, "scores"),
                    rs.getString("trace_id"),
                    rs.getObject("finished_at", OffsetDateTime.class).toInstant());
        }, runId);
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
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
