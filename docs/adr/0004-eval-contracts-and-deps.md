# ADR 0004 — 评测引擎的数据契约与依赖选择

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M2、[ADR 0001](0001-otel-genai-as-data-contract.md)

## 背景

评测引擎要"重放跑分"：数据集从线上 trace 固化，Runner 对目标 Agent 批量重放，评分后按 case 关联成本与延迟。三个契约问题需要先定：

1. 平台如何调用被测 Agent（重放协议）
2. 重放产生的 span 如何与 case 关联（成本/延迟归因）
3. JSON Schema 校验依赖怎么选

## 决策

1. **重放协议（HTTP）**：Runner 对目标 Agent `POST {target_url}`，请求体
   `{"run_id": "...", "case_id": "...", "input": <case.input 原样 JSON>}`，
   期望响应 `{"output": "<文本>", "trace_id": "<可选，hex>"}`；非 JSON 响应按原文当作 output。
   trace_id 是成本归因的首选通道。

2. **span 扩展属性**：目标 Agent 上报 span 时携带
   `agentlens.eval.run_id` / `agentlens.eval.case_id`（遵循 ADR 0001 的 `agentlens.*` 前缀约定）。
   平台按这两个属性聚合 token 与成本，作为 trace_id 缺失时的兜底；两者同时存在时按
   (trace_id, span_id) 去重，避免双计。

3. **JSON Schema 校验依赖：`com.networknt:json-schema-validator` 1.5.8**（而非 3.0.x）。
   理由：3.0.x 已迁移到 Jackson 3（`tools.jackson.*`），与本平台 Spring Boot 3.5 BOM 的
   Jackson 2（`com.fasterxml.*`）不兼容——引入它等于同时跑两套 JSON 运行时。1.5.8 原生
   Jackson 2、API 稳定、支持 draft 2020-12。升级到 3.x 需等平台先迁 Jackson 3（Boot 4 时代），另立 ADR。

4. **LLM-as-judge 走 OpenAI 兼容 `/chat/completions`**：base URL / model / API key 全部环境变量
   （`JUDGE_BASE_URL` / `JUDGE_API_KEY` / `JUDGE_MODEL`），平台不引入任何 LLM SDK——与"裸 OTLP
   即接入"同一哲学。判卷要求模型只回 JSON：`{"verdict": "PASS"|"FAIL", "reason": "..."}`。

## 后果

- 正：目标 Agent 零侵入（一个 HTTP 端点即可被测）；成本归因有主备两条通道；JSON 栈保持单一
- 负：重放要求被测 Agent 提供同步 HTTP 端点，CLI 直跑本地函数的场景要等 `cli/` 的门禁形态；
  `agentlens.eval.*` 属性需要 Agent 侧主动埋（示例与 sdk-java 会提供）
- 中性：评分在 Runner 侧执行，case 快照随 run 落库，之后数据集改动不影响历史 run
