# agent-lens 路线图

单二进制、自托管的 Agent 可观测性与评测平台。数据契约与存储决策见 [adr/](adr/)。

## M0 — 骨架（已完成 2026-09-27）

- [x] 仓库结构：`server/`、`sdk-java/`、`cli/`、`docs/`
- [x] 路线图与 ADR 0001（数据契约）、0002（存储与分发形态）

## M1 — 采集与看板（第 1 周）

- [x] `server/`：Spring Boot 3 工程 + `POST /v1/traces`（OTLP/JSON 编码）端点
- [x] span 解析落库（PostgreSQL），保留 `gen_ai.*` 属性原样
- [x] Trace 瀑布图：按 trace 聚合 span，展示 LLM 调用 / 工具调用 / 耗时
- [x] Token 用量与成本核算（模型价目表 × `gen_ai.usage.*`）
- [x] `examples/`：一个可发送 `gen_ai.*` span 的最小示例 Agent

**验收**：任一 LangChain4j demo 用 OTel 自动埋点，10 分钟内在平台看到 waterfall 和这条调用的成本。

## M2 — 评测引擎（第 2 周，已完成 2026-09-27）

- [x] 数据集管理（JSONL 格式，case 可从线上 trace 一键固化）
- [x] 重放 Runner：对数据集批量跑目标 Agent
- [x] 三种评分器：精确匹配 / JSON Schema 校验 / LLM-as-judge
- [x] Run 对比视图：两次 run 的通过率、成本、延迟 diff

**验收**：改一版 prompt 重跑，平台展示成功率与成本的变化对比。

## M3 — 门禁与真实接入（第 3 周）

- [x] `cli/`：`agentlens eval run --gate`，评测不达标退出码非零，可挂 CI（契约见 ADR 0005）
- [x] `sdk-java/`：Spring AI / LangChain4j 一行接入的薄 SDK（OTel API + OTLP/JSON exporter，ADR 0006）
- [ ] 接入两个真实异构 Agent（一个 Python CLI 项目、一个 Java 平台项目）作为活体示例
- [ ] 发布 v0.1.0 + 博客一篇

**验收**：第三方仓库按 README 十分钟完成接入并跑通一次门禁评测。
