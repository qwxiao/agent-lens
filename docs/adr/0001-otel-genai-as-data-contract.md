# ADR 0001 — 数据契约采用 OTel GenAI 语义约定

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M1

## 背景

平台要支持"任何 Agent 项目都能接入"。如果契约是自造的 SDK 协议，接入即绑定；如果每个语言各造一套，维护成本失控。

## 决策

1. 采集协议用标准 **OTLP**（HTTP/JSON 与 gRPC）。
2. span 属性对齐 **OpenTelemetry GenAI 语义约定**（`gen_ai.system`、`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、工具调用 span 等）。
3. 接入方式三条路，成本递减：
   - OpenTelemetry 生态自动埋点（如 OpenLLMetry），零代码
   - `sdk-java` 薄 SDK：Spring AI / LangChain4j 一行接入
   - 裸 OTLP/HTTP：任何语言、甚至 curl 直接上报

## 后果

- 正：任何能发 OTLP 的进程即接入；生态工具（collector、仪表盘）天然兼容；SDK 不是接入的必要条件
- 负：受限于语义约定当前覆盖范围，平台特有字段需加 `agentlens.*` 前缀扩展，并随约定演进迁移
- 中性：评测引擎复用同一份 trace 数据（评测集从真实 trace 固化），无需第二条数据链路
