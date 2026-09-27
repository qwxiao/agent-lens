"""agent-lens demo agent in Python — stdlib only, no pip installs.

Two modes:

  python agent.py            start the replay endpoint (default :9000), ready to be
                             evaluated by `agentlens eval run` (ADR 0004 protocol)
  python agent.py --demo     send one fake agent run straight to the platform, like
                             examples/minimal-agent does in Java

Both modes speak raw OTLP/HTTP with JSON encoding — the wire format any language
can produce, no OpenTelemetry SDK required. Replayed spans carry
`agentlens.eval.run_id` / `agentlens.eval.case_id`, and the replay response returns
`trace_id` so the platform attributes cost on the preferred channel.

Configuration (environment variables):
  AGENTLENS_URL   platform base URL (default http://localhost:8080)
  PORT            replay endpoint port (default 9000)
  SERVICE_NAME    service.name resource attribute (default python-demo-agent)
"""

import json
import os
import random
import re
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AGENTLENS_URL = os.environ.get("AGENTLENS_URL", "http://localhost:8080").rstrip("/")
PORT = int(os.environ.get("PORT", "9000"))
SERVICE_NAME = os.environ.get("SERVICE_NAME", "python-demo-agent")
MODEL = "gpt-4o-mini"

ADD_PATTERN = re.compile(r"^\s*(-?\d+)\s*\+\s*(-?\d+)\s*$")


def answer(question: str) -> str:
    """The fake 'LLM': deterministic enough for exact-match eval cases."""
    match = ADD_PATTERN.match(question)
    if match:
        return str(int(match.group(1)) + int(match.group(2)))
    if "say hi" in question.lower() or question.lower().strip() == "hi":
        return "hello"
    return "i don't know"


def hex_id(byte_count: int) -> str:
    return format(random.getrandbits(byte_count * 8), f"0{byte_count * 2}x")


def attribute(key: str, value) -> dict:
    if isinstance(value, bool):
        encoded = {"boolValue": value}
    elif isinstance(value, int):
        # OTLP JSON encodes 64-bit ints as strings (proto3 JSON mapping)
        encoded = {"intValue": str(value)}
    elif isinstance(value, float):
        encoded = {"doubleValue": value}
    else:
        encoded = {"stringValue": value}
    return {"key": key, "value": encoded}


def span(trace_id: str, span_id: str, parent_span_id: str | None, name: str,
         kind: str, start_ns: int, end_ns: int, attributes: list) -> dict:
    result = {
        "traceId": trace_id,
        "spanId": span_id,
        "name": name,
        "kind": kind,
        "startTimeUnixNano": str(start_ns),
        "endTimeUnixNano": str(end_ns),
        "attributes": attributes,
        "status": {"code": "STATUS_CODE_OK"},
    }
    if parent_span_id:
        result["parentSpanId"] = parent_span_id
    return result


def trace_payload(trace_id: str, spans: list) -> dict:
    return {
        "resourceSpans": [{
            "resource": {"attributes": [
                attribute("service.name", SERVICE_NAME),
                attribute("service.version", "0.1.0"),
            ]},
            "scopeSpans": [{
                "scope": {"name": "io.agentlens.examples.python", "version": "0.1.0"},
                "spans": spans,
            }],
        }]
    }


def llm_attributes(input_tokens: int, output_tokens: int, run_id: str | None,
                   case_id: str | None) -> list:
    attributes = [
        attribute("gen_ai.system", "openai"),
        attribute("gen_ai.operation.name", "chat"),
        attribute("gen_ai.request.model", MODEL),
        attribute("gen_ai.usage.input_tokens", input_tokens),
        attribute("gen_ai.usage.output_tokens", output_tokens),
    ]
    if run_id:
        attributes.append(attribute("agentlens.eval.run_id", run_id))
    if case_id:
        attributes.append(attribute("agentlens.eval.case_id", case_id))
    return attributes


def report(trace_payload_dict: dict) -> None:
    request = urllib.request.Request(
        AGENTLENS_URL + "/v1/traces",
        data=json.dumps(trace_payload_dict).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(request, timeout=10) as response:
        response.read()


def run_replay(run_id: str, case_id: str, case_input: dict) -> tuple[str, str]:
    """One replayed case: produce the output and report its trace. Returns (output, trace_id)."""
    question = str(case_input.get("question", json.dumps(case_input)))
    output = answer(question)
    trace_id = hex_id(16)
    root_span_id = hex_id(8)
    now = time.time_ns()
    report(trace_payload(trace_id, [
        span(trace_id, root_span_id, None, "answer-question", "SPAN_KIND_SERVER", now, now + 40_000_000, []),
        span(trace_id, hex_id(8), root_span_id, f"chat {MODEL}", "SPAN_KIND_INTERNAL",
             now + 5_000_000, now + 35_000_000,
             llm_attributes(len(question) + 60, len(output) + 15, run_id, case_id)),
    ]))
    return output, trace_id


class ReplayHandler(BaseHTTPRequestHandler):
    """POST /replay {"run_id","case_id","input"} -> {"output","trace_id"} (ADR 0004)."""

    def do_POST(self):  # noqa: N802 (http.server API)
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        try:
            output, trace_id = run_replay(
                body.get("run_id"), body.get("case_id"), body.get("input") or {})
            self._respond(200, {"output": output, "trace_id": trace_id})
        except Exception as error:  # noqa: BLE001 - surface any failure to the runner
            self._respond(500, {"error": str(error)})

    def _respond(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):  # noqa: A002 - quieter default logging
        pass


def demo() -> None:
    """One standalone trace with an LLM call and a tool call, like the Java example."""
    trace_id = hex_id(16)
    root = hex_id(8)
    now = time.time_ns()
    ms = 1_000_000
    tool_span = span(trace_id, hex_id(8), root, "execute_tool get_weather", "SPAN_KIND_INTERNAL",
                     now + 850 * ms, now + 1150 * ms, [
                         attribute("gen_ai.operation.name", "execute_tool"),
                         attribute("gen_ai.tool.name", "get_weather"),
                     ])
    report(trace_payload(trace_id, [
        span(trace_id, root, None, "answer-question", "SPAN_KIND_SERVER", now, now + 2400 * ms, []),
        span(trace_id, hex_id(8), root, f"chat {MODEL}", "SPAN_KIND_INTERNAL",
             now + 50 * ms, now + 750 * ms, llm_attributes(1240, 356, None, None)),
        tool_span,
        span(trace_id, hex_id(8), root, f"chat {MODEL}", "SPAN_KIND_INTERNAL",
             now + 1250 * ms, now + 2300 * ms, llm_attributes(1980, 512, None, None)),
    ]))
    print(f"Trace {trace_id} sent to {AGENTLENS_URL} — open the dashboard to see it.")


def main() -> None:
    if "--demo" in sys.argv:
        demo()
        return
    server = ThreadingHTTPServer(("0.0.0.0", PORT), ReplayHandler)
    print(f"replay endpoint on http://localhost:{PORT}/replay (platform: {AGENTLENS_URL})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
