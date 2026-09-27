# agent-lens

> Lightweight, self-hosted observability & evaluation platform for LLM agents.
> 轻量、自托管的 Agent 可观测性与评测平台。

**Status: M3 in progress — OTLP ingestion, trace waterfall, cost accounting and the evaluation engine (datasets, replay runs, scorers, run comparison) are working; the CI gate CLI just landed.** See [docs/PLAN.md](docs/PLAN.md) for the roadmap.

## Why

Existing agent observability tools are either heavy to self-host or SaaS-only, and the Java ecosystem has no lightweight equivalent. agent-lens is a single-binary platform that speaks standard OpenTelemetry: any agent that can emit OTLP — Java, Python, Node, anything — can plug in without vendor SDK lock-in.

## Quick start

Requires Docker and JDK 25 (versions are locked in [ADR 0003](docs/adr/0003-tech-stack-versions.md)).

```bash
# 1. PostgreSQL
docker compose up -d postgres

# 2. the platform (Maven Wrapper is bundled, no local Maven needed)
./mvnw spring-boot:run -pl server

# 3. watch a demo agent send one run over raw OTLP/HTTP
./mvnw -pl examples/minimal-agent compile exec:java
```

Open **http://localhost:8080**: the demo trace shows up with its waterfall, LLM/tool
call badges, and the token cost computed from the default price list.

## Ingestion

`POST /v1/traces` accepts OTLP/HTTP with JSON encoding (gzip accepted) and stores
spans in PostgreSQL with `gen_ai.*` attributes verbatim, per the
[OpenTelemetry GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/).
Three ways to plug in:

1. **OpenTelemetry auto-instrumentation** (e.g. OpenLLMetry) — zero code, point it at the endpoint
2. **`sdk-java` thin client** — two lines: standard OpenTelemetry wired to agent-lens,
   see below
3. **Raw OTLP/HTTP** — see [examples/minimal-agent](examples/minimal-agent) for a zero-dependency demo

Costs are computed at query time from `gen_ai.usage.*` against
[`server/src/main/resources/model-prices.yaml`](server/src/main/resources/model-prices.yaml)
(USD per 1M tokens, approximate list prices — point `PRICE_FILE` at your own copy to override).

### sdk-java

The SDK hands you a standard OpenTelemetry instance exporting OTLP/JSON to the
platform — no protobuf, no proprietary API ([ADR 0006](docs/adr/0006-sdk-java-otel-json-exporter.md)):

```java
try (AgentLens lens = AgentLens.create("http://localhost:8080", "my-agent")) {
    OpenTelemetry otel = lens.openTelemetry();
    // hand `otel` to your framework's OTel integration (Spring AI, LangChain4j, …)

    // while replaying eval case k of run r, tag spans for cost attribution:
    EvalScope.set("r", "k");
    try {
        /* call the agent */
    } finally {
        EvalScope.clear();
    }
}  // close() flushes pending spans
```

## Query API

```
GET /api/traces?limit=50&offset=0   → { "traces": [TraceSummary], "total": n }
GET /api/traces/{traceId}           → TraceDetail (summary + ordered spans for the waterfall)
```

## Evaluation (M2)

Replay datasets against a target agent and score the outputs — the target only needs
one synchronous HTTP endpoint ([ADR 0004](docs/adr/0004-eval-contracts-and-deps.md)):

```
POST /api/datasets                        {"name": "..."}                     → Dataset
PUT  /api/datasets/{id}/cases             JSONL, one case per line            → {"upserted": n}
POST /api/datasets/{id}/cases/from-trace/{traceId}                            → snapshot a trace as a case
POST /api/eval-runs                       {"datasetId", "targetUrl",          → 202, run executes
                                           "scorers": ["exact_match",            asynchronously
                                           "json_schema", "llm_judge"]}
GET  /api/eval-runs/{id}                  → per-case results, scores, attributed cost
GET  /api/eval-runs/{a}/compare/{b}       → pass-rate / latency / cost diff per case
```

Replayed agents either answer with `{"output": "...", "trace_id": "..."}` (trace_id is
the preferred cost-attribution channel) or tag their spans with
`agentlens.eval.run_id` / `agentlens.eval.case_id` as the fallback. The LLM judge speaks
any OpenAI-compatible `/chat/completions` endpoint via `JUDGE_BASE_URL` / `JUDGE_API_KEY`
/ `JUDGE_MODEL`.

## CI gate

The `agentlens` CLI starts an evaluation run on the platform, waits for it to settle
and turns the outcome into a CI exit code — datasets, scorers and cost attribution
stay in one place, the server ([ADR 0005](docs/adr/0005-cli-gate-contract.md)):

```bash
./mvnw -pl cli package                # → cli/target/agentlens.jar (executable fat jar)

java -jar cli/target/agentlens.jar eval run \
  --dataset <dataset-id> \
  --target http://localhost:9000/replay \
  --scorers exact_match,json_schema \
  --gate 0.9
```

Exit codes: `0` run completed and the gate is met (or no gate requested), `1` run
completed but the pass rate is below `--gate`, `2` bad usage / platform unreachable /
run failed / timed out. Point `--server` (or the `AGENTLENS_URL` environment variable)
at a non-default platform.

## Architecture

```
any agent (Java / Python / Node / ...)
   │  OTLP/HTTP JSON (spans with gen_ai.* attributes)
   ▼
┌──────────────────────────────────┐
│ agent-lens (single binary)       │
│  ingest → store → serve → eval   │
│  storage: PostgreSQL             │
└──────────────────────────────────┘
```

Design decisions are recorded as ADRs in [docs/adr/](docs/adr/) — the data contract
([ADR 0001](docs/adr/0001-otel-genai-as-data-contract.md)), the single-binary + PostgreSQL
distribution ([ADR 0002](docs/adr/0002-single-binary-postgres.md)), locked versions
([ADR 0003](docs/adr/0003-tech-stack-versions.md)), the evaluation contracts
([ADR 0004](docs/adr/0004-eval-contracts-and-deps.md)), the CLI gate contract
([ADR 0005](docs/adr/0005-cli-gate-contract.md)) and the SDK shape
([ADR 0006](docs/adr/0006-sdk-java-otel-json-exporter.md)).

## Repository layout

```
server/                Spring Boot service: ingestion, storage, API (dashboard UI included)
examples/              Runnable demos (raw OTLP/HTTP agent)
sdk-java/              Thin client SDK: standard OTel wired to agent-lens (ADR 0006)
cli/                   agentlens CLI: eval runner + CI gate
docs/                  Roadmap and architecture decision records (zh-CN)
```

## Development

```bash
./mvnw test        # unit + Testcontainers integration tests (Docker required)
```

## Roadmap

See [docs/PLAN.md](docs/PLAN.md).

## License

TBD before first public release.
