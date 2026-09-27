-- Evaluation engine tables (ADR 0004). These are small metadata tables —
-- unlike spans, they are not time-partitioned.

CREATE TABLE datasets (
    id          text PRIMARY KEY,
    name        text NOT NULL,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dataset_cases (
    id               text PRIMARY KEY,
    dataset_id       text NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    input            jsonb       NOT NULL,
    expected_output  text,
    json_schema      jsonb,
    tags             jsonb       NOT NULL DEFAULT '[]'::jsonb,
    metadata         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    source_trace_id  text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX dataset_cases_dataset_idx ON dataset_cases (dataset_id, created_at);

CREATE TABLE eval_runs (
    id          text PRIMARY KEY,
    dataset_id  text NOT NULL REFERENCES datasets(id),
    name        text NOT NULL,
    status      text NOT NULL DEFAULT 'running',
    target_url  text NOT NULL,
    scorers     jsonb       NOT NULL DEFAULT '[]'::jsonb,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE eval_run_cases (
    id          bigserial PRIMARY KEY,
    run_id      text NOT NULL REFERENCES eval_runs(id) ON DELETE CASCADE,
    case_id     text NOT NULL,
    case_input  jsonb       NOT NULL,
    output      text,
    error       text,
    latency_ms  bigint,
    passed      boolean,
    scores      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    trace_id    text,
    finished_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX eval_run_cases_run_idx ON eval_run_cases (run_id, id);

-- Speeds up eval-cost lookups that filter spans by attribute value.
CREATE INDEX spans_attributes_gin ON spans USING gin (attributes);
