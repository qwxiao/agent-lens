# agent-lens

> Lightweight, self-hosted observability & evaluation platform for LLM agents.
> 轻量、自托管的 Agent 可观测性与评测平台。

**Status: M1 in active development — OTLP ingestion, trace waterfall and cost accounting are working; evaluation engine is next.** See [docs/PLAN.md](docs/PLAN.md) for the roadmap.

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
2. **`sdk-java` thin client** (planned) — one-line setup for Spring AI / LangChain4j
3. **Raw OTLP/HTTP** — see [examples/minimal-agent](examples/minimal-agent) for a zero-dependency demo

Costs are computed at query time from `gen_ai.usage.*` against
[`server/src/main/resources/model-prices.yaml`](server/src/main/resources/model-prices.yaml)
(USD per 1M tokens, approximate list prices — point `PRICE_FILE` at your own copy to override).

## Query API

```
GET /api/traces?limit=50&offset=0   → { "traces": [TraceSummary], "total": n }
GET /api/traces/{traceId}           → TraceDetail (summary + ordered spans for the waterfall)
```

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
distribution ([ADR 0002](docs/adr/0002-single-binary-postgres.md)), and locked versions
([ADR 0003](docs/adr/0003-tech-stack-versions.md)).

## Repository layout

```
server/                Spring Boot service: ingestion, storage, API (dashboard UI included)
examples/              Runnable demos (raw OTLP/HTTP agent)
sdk-java/              Thin Java client SDK (planned)
cli/                   agentlens CLI: eval runner + CI gate (planned)
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
