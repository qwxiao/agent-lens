package io.agentlens.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Repository
public class PostgresSpanStore implements SpanStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INSERT = """
            INSERT INTO spans (trace_id, span_id, parent_span_id, name, span_kind,
                               start_time, end_time, service_name, scope_name, scope_version,
                               status_code, status_message, attributes, resource_attributes,
                               events, links)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
            ON CONFLICT (trace_id, span_id, start_time) DO NOTHING
            """;

    private static final String SELECT = """
            SELECT trace_id, span_id, parent_span_id, name, span_kind, start_time, end_time,
                   service_name, scope_name, scope_version, status_code, status_message,
                   attributes, resource_attributes, events, links
            FROM spans
            WHERE trace_id = ANY (?)
            ORDER BY start_time, span_id
            """;

    private final JdbcTemplate jdbc;

    public PostgresSpanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insert(Collection<SpanRecord> spans) {
        if (spans.isEmpty()) {
            return 0;
        }
        List<SpanRecord> batch = new ArrayList<>(spans);
        int[][] counts = jdbc.batchUpdate(INSERT, batch, 500,
                (java.sql.PreparedStatement ps, SpanRecord span) -> {
            int i = 1;
            ps.setString(i++, span.traceId());
            ps.setString(i++, span.spanId());
            ps.setString(i++, span.parentSpanId());
            ps.setString(i++, span.name());
            ps.setString(i++, span.spanKind());
            ps.setObject(i++, OffsetDateTime.ofInstant(span.startTime(), ZoneOffset.UTC));
            ps.setObject(i++, OffsetDateTime.ofInstant(span.endTime(), ZoneOffset.UTC));
            ps.setString(i++, span.serviceName());
            ps.setString(i++, span.scopeName());
            ps.setString(i++, span.scopeVersion());
            ps.setString(i++, span.statusCode());
            ps.setString(i++, span.statusMessage());
            ps.setString(i++, span.attributes().toString());
            ps.setString(i++, span.resourceAttributes().toString());
            ps.setString(i++, span.events().toString());
            ps.setString(i++, span.links().toString());
        });
        return Arrays.stream(counts)
                .flatMapToInt(Arrays::stream)
                .sum();
    }

    @Override
    public List<String> recentTraceIds(int limit, int offset) {
        return jdbc.queryForList(
                "SELECT trace_id FROM spans GROUP BY trace_id ORDER BY max(start_time) DESC LIMIT ? OFFSET ?",
                String.class, limit, offset);
    }

    @Override
    public long countTraces() {
        Long count = jdbc.queryForObject("SELECT count(DISTINCT trace_id) FROM spans", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<SpanRecord> findByTraceIds(Collection<String> traceIds) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        String[] ids = traceIds.toArray(String[]::new);
        return jdbc.query(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(SELECT);
            ps.setArray(1, con.createArrayOf("text", ids));
            return ps;
        }, (rs, rowNum) -> mapRow(rs));
    }

    private static SpanRecord mapRow(ResultSet rs) throws SQLException {
        return new SpanRecord(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("name"),
                rs.getString("span_kind"),
                rs.getObject("start_time", OffsetDateTime.class).toInstant(),
                rs.getObject("end_time", OffsetDateTime.class).toInstant(),
                rs.getString("service_name"),
                rs.getString("scope_name"),
                rs.getString("scope_version"),
                rs.getString("status_code"),
                rs.getString("status_message"),
                jsonNode(rs, "attributes"),
                jsonNode(rs, "resource_attributes"),
                jsonNode(rs, "events"),
                jsonNode(rs, "links"));
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
