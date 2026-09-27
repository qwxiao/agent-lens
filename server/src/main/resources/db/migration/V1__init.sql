-- One row per OTLP span. Partitioned by start_time (ADR 0002): monthly range partitions
-- are created ahead by the deployment (see docs/runbook), the DEFAULT partition catches
-- anything outside prepared months so ingestion never fails on the calendar.
CREATE TABLE spans (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    trace_id            text        NOT NULL,
    span_id             text        NOT NULL,
    parent_span_id      text,
    name                text        NOT NULL,
    span_kind           text        NOT NULL DEFAULT 'INTERNAL',
    start_time          timestamptz NOT NULL,
    end_time            timestamptz NOT NULL,
    service_name        text,
    scope_name          text,
    scope_version       text,
    status_code         text,
    status_message      text,
    attributes          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    resource_attributes jsonb       NOT NULL DEFAULT '{}'::jsonb,
    events              jsonb       NOT NULL DEFAULT '[]'::jsonb,
    links               jsonb       NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (id, start_time),
    CONSTRAINT spans_dedup UNIQUE (trace_id, span_id, start_time)
) PARTITION BY RANGE (start_time);

CREATE INDEX spans_trace_idx   ON spans (trace_id, start_time);
CREATE INDEX spans_recent_idx  ON spans (start_time DESC);

CREATE TABLE spans_2026_09 PARTITION OF spans
    FOR VALUES FROM ('2026-09-01T00:00:00Z') TO ('2026-10-01T00:00:00Z');
CREATE TABLE spans_2026_10 PARTITION OF spans
    FOR VALUES FROM ('2026-10-01T00:00:00Z') TO ('2026-11-01T00:00:00Z');
CREATE TABLE spans_2026_11 PARTITION OF spans
    FOR VALUES FROM ('2026-11-01T00:00:00Z') TO ('2026-12-01T00:00:00Z');
CREATE TABLE spans_default PARTITION OF spans DEFAULT;
