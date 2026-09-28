# Changelog

## 0.1.0 — 2026-09-28

First public release: a single-binary, self-hosted observability and evaluation
platform for LLM agents, speaking standard OpenTelemetry.

- OTLP/HTTP JSON ingestion (`POST /v1/traces`, gzip accepted) — `gen_ai.*` attributes
  stored verbatim per the OpenTelemetry GenAI semantic conventions
- Trace waterfall dashboard with LLM/tool call badges; token cost computed from
  `gen_ai.usage.*` against a model price table
- Evaluation engine: datasets with JSONL import and one-click trace snapshotting;
  replay runner over a plain HTTP replay protocol; exact-match, json-schema and
  llm-judge scorers; run comparison with per-case pass-rate/latency/cost diff
- `agentlens` CLI: `eval run --gate` CI gate with three exit codes — 0 gate met,
  1 gate not met, 2 operational failure (ADR 0005)
- `sdk-java` thin client SDK: standard OpenTelemetry wired to agent-lens over
  OTLP/JSON, plus `EvalScope` attribution for replayed spans (ADR 0006)
- Examples: raw OTLP/HTTP demo agents in Java and stdlib-only Python — the Python
  one is evaluable end to end through the CLI gate
