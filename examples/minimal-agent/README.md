# Minimal demo agent (raw OTLP/HTTP)

Sends one fake agent run — a root agent span, two LLM calls, one tool call, all
annotated with `gen_ai.*` attributes — straight to agent-lens over OTLP/HTTP JSON.
No OpenTelemetry SDK, no dependencies beyond the JDK: this shows the wire format
any language can produce.

## Run

```bash
# 1. start the platform
docker compose up -d postgres
./mvnw spring-boot:run -pl server

# 2. from another shell, send the demo trace
./mvnw -pl examples/minimal-agent compile exec:java

# 3. open http://localhost:8080
```

The trace appears in the dashboard with its waterfall and token cost
(`gpt-4o-mini` is in the default price list).

## Real instrumentation

This example hand-crafts the OTLP payload for clarity. Real agents instrument
themselves instead:

- **OpenLLMetry auto-instrumentation** — attach the agent and set
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/json`,
  `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080/v1/traces`
- **LangChain4j / Spring AI** — use the `sdk-java` thin client
- **Anything that speaks OTLP** — point it at `/v1/traces` (HTTP, JSON encoding;
  gzip is accepted)
