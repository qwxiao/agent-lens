"""Unit tests for the demo agent's span encoding and replay logic — stdlib only.

Run from examples/python-agent:  python -m unittest test_agent -v
"""

import unittest
from unittest.mock import patch

import agent


class AnswerTest(unittest.TestCase):

    def test_arithmetic(self):
        self.assertEqual(agent.answer("17+25"), "42")
        self.assertEqual(agent.answer(" 100 + 200 "), "300")
        self.assertEqual(agent.answer("2-1"), "i don't know")

    def test_greeting(self):
        self.assertEqual(agent.answer("say hi"), "hello")
        self.assertEqual(agent.answer("hi"), "hello")
        self.assertEqual(agent.answer("which one"), "i don't know")

    def test_fallback(self):
        self.assertEqual(agent.answer("what is the meaning of life"), "i don't know")


class HexIdTest(unittest.TestCase):

    def test_lengths_and_charset(self):
        for byte_count, length in ((8, 16), (16, 32)):
            value = agent.hex_id(byte_count)
            self.assertEqual(len(value), length)
            int(value, 16)


class SpanEncodingTest(unittest.TestCase):

    def test_span_shape(self):
        span = agent.span("t" * 32, "s" * 16, None, "chat", "SPAN_KIND_INTERNAL",
                          100, 200, [agent.attribute("k", "v")])
        self.assertEqual(span["traceId"], "t" * 32)
        self.assertEqual(span["startTimeUnixNano"], "100")
        self.assertNotIn("parentSpanId", span)
        self.assertEqual(span["attributes"][0]["value"], {"stringValue": "v"})

    def test_parent_is_kept_when_present(self):
        span = agent.span("t" * 32, "s" * 16, "p" * 16, "chat", "SPAN_KIND_INTERNAL", 1, 2, [])
        self.assertEqual(span["parentSpanId"], "p" * 16)

    def test_attribute_value_types(self):
        self.assertEqual(agent.attribute("s", "x")["value"], {"stringValue": "x"})
        self.assertEqual(agent.attribute("i", 7)["value"], {"intValue": "7"})
        self.assertEqual(agent.attribute("f", 0.5)["value"], {"doubleValue": 0.5})
        self.assertEqual(agent.attribute("b", True)["value"], {"boolValue": True})

    def test_trace_payload_carries_service_and_scope(self):
        payload = agent.trace_payload("t" * 32, [])
        resource_spans = payload["resourceSpans"][0]
        service = next(a for a in resource_spans["resource"]["attributes"]
                       if a["key"] == "service.name")
        self.assertEqual(service["value"]["stringValue"], agent.SERVICE_NAME)
        self.assertEqual(resource_spans["scopeSpans"][0]["scope"]["name"],
                         "io.agentlens.examples.python")


class EvalAttributionTest(unittest.TestCase):

    def test_llm_attributes_carry_eval_tags_when_replaying(self):
        attributes = agent.llm_attributes(10, 5, "run-1", "case-2")
        by_key = {a["key"]: a["value"] for a in attributes}
        self.assertEqual(by_key["agentlens.eval.run_id"], {"stringValue": "run-1"})
        self.assertEqual(by_key["agentlens.eval.case_id"], {"stringValue": "case-2"})
        self.assertEqual(by_key["gen_ai.usage.input_tokens"], {"intValue": "10"})

    def test_llm_attributes_omit_eval_tags_outside_replay(self):
        by_key = {a["key"] for a in agent.llm_attributes(10, 5, None, None)}
        self.assertNotIn("agentlens.eval.run_id", by_key)
        self.assertNotIn("agentlens.eval.case_id", by_key)


class ReplayTest(unittest.TestCase):

    def test_run_replay_reports_tagged_trace_and_returns_trace_id(self):
        captured = {}
        with patch.object(agent, "report", lambda payload: captured.update(payload)):
            output, trace_id = agent.run_replay("run-9", "case-8", {"question": "2+2"})

        self.assertEqual(output, "4")
        self.assertEqual(len(trace_id), 32)
        spans = captured["resourceSpans"][0]["scopeSpans"][0]["spans"]
        self.assertEqual(len(spans), 2)
        root, llm = spans
        self.assertEqual(root["name"], "answer-question")
        self.assertEqual(llm["parentSpanId"], root["spanId"])
        by_key = {a["key"]: a["value"] for a in llm["attributes"]}
        self.assertEqual(by_key["agentlens.eval.run_id"], {"stringValue": "run-9"})
        self.assertEqual(by_key["agentlens.eval.case_id"], {"stringValue": "case-8"})


if __name__ == "__main__":
    unittest.main()
