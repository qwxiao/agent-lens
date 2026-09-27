# Python demo agent (replay endpoint + raw OTLP/HTTP)

A heterogeneous counterpart to [examples/minimal-agent](../minimal-agent): the same
wire format, written in Python with **zero pip dependencies** (stdlib only). It can
be evaluated end to end by the `agentlens` CLI — this is the "any language that can
speak OTLP" story, exercised for real.

The agent exposes the replay endpoint from [ADR 0004](../../docs/adr/0004-eval-contracts-and-deps.md):
`POST /replay` with `{"run_id", "case_id", "input"}` → `{"output", "trace_id"}`.
Its spans carry `agentlens.eval.run_id` / `agentlens.eval.case_id`, and the response
returns `trace_id` — both cost-attribution channels, so the platform can bill every
replayed case.

## Run the eval flow

```bash
# 1. start the platform
docker compose up -d postgres
./mvnw spring-boot:run -pl server

# 2. create a dataset and import the seed cases
DATASET=$(curl -s -X POST http://localhost:8080/api/datasets \
  -H 'content-type: application/json' -d '{"name":"python-agent-eval"}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -X PUT http://localhost:8080/api/datasets/$DATASET/cases \
  -H 'content-type: application/x-ndjson' --data-binary @dataset.jsonl

# 3. build the CLI and start the agent
./mvnw -pl cli package
python agent.py        # replay endpoint on :9000 (PORT / AGENTLENS_URL to override)

# 4. run the CI gate — the seed dataset scores 75% (3 of 4 cases)
java -jar cli/target/agentlens.jar eval run --dataset $DATASET \
  --target http://localhost:9000/replay --scorers exact_match --gate 0.9   # exit 1
java -jar cli/target/agentlens.jar eval run --dataset $DATASET \
  --target http://localhost:9000/replay --scorers exact_match --gate 0.5   # exit 0
```

Both runs appear on the dashboard with per-case scores, latency and attributed cost.

## Demo mode

```bash
python agent.py --demo   # send one standalone trace (agent + 2 LLM calls + 1 tool call)
```

## Tests

```bash
python -m unittest test_agent -v
```

## Real instrumentation

This example hand-crafts the OTLP payload for clarity. Real Python agents
instrument themselves instead:

- **OpenLLMetry auto-instrumentation** — set
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/json`,
  `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080/v1/traces`
- **Anything that speaks OTLP** — point it at `/v1/traces` (HTTP, JSON encoding;
  gzip is accepted), and add a ten-line replay endpoint like `ReplayHandler`
  above to become evaluable
