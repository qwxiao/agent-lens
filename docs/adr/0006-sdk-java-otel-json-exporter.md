# ADR 0006 — sdk-java 的形态：标准 OTel + 自研 OTLP/JSON exporter

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M3、[ADR 0001](0001-otel-genai-as-data-contract.md)、[ADR 0004](0004-eval-contracts-and-deps.md)

## 背景

M3 要交付 `sdk-java/`：Spring AI / LangChain4j 场景"一行接入"。问题：SDK 依赖什么、
导出编码走哪条、eval 归因属性怎么埋。

## 决策

1. **走标准 OpenTelemetry API/SDK，版本锁 1.66.0**（本模块开工时的最新 GA）。用户拿到的是
   标准 `OpenTelemetry` 实例，交给框架的 OTel 集成即可；SDK 不发明任何自有 API。
2. **自研 `OtlpJsonHttpSpanExporter`，OTLP/JSON 编码，零 protobuf 依赖**：平台 `/v1/traces`
   只说 JSON（ADR 0001 的最小实现），官方 `opentelemetry-exporter-otlp` 只发 protobuf——
   引它就得让平台先支持 protobuf。编码用手写 JSON 序列化（int64 按proto3 JSON 映射编码为
   字符串），依赖面只剩 `opentelemetry-sdk` + JDK HttpClient。等平台支持 protobuf 后换官方
   exporter 并废弃此类，另立 ADR。
3. **eval 归因属性由 SDK 自动埋**：`EvalScope`（ThreadLocal 的 run/case id）+
   `EvalScopeProcessor`（start 时给 span 打 `agentlens.eval.run_id` / `agentlens.eval.case_id`），
   兑现 ADR 0004 "示例与 sdk-java 会提供埋点"的承诺。跨线程/异步的场景不覆盖，Agent 可自行
   setAttribute（常量公开在 `EvalScope`）。
4. **`AgentLens.create(endpoint, serviceName)` 是唯一入口**：建 SdkTracerProvider（service.name
   资源 + EvalScopeProcessor + BatchSpanProcessor）并返回句柄；`close()` 触发 flush。
   不写 GlobalOpenTelemetry，避免多 SDK 冲突，由调用方决定装配方式。
5. **不做协议扩展**：除 ADR 0004 已定的 `agentlens.eval.*` 属性外，SDK 不新增任何属性或
   请求头（AGENTS.md 硬约定 1）。

## 后果

- 正：Agent 侧接入是两行代码（create + close）；数据契约与 ADR 0001 完全一致
- 负：OTLP/JSON 编码由本仓库维护，OTel 规格变更时需要跟进；`getVersion()` 等返回 null 的
  兼容细节要靠测试钉住
- 中性：Spring AI / LangChain4j 的具体接入写法在 M3 的真实 Agent 接入中验证，SDK 本身
  不依赖任何框架
