# ADR 0005 — CLI 门禁的形态与退出码契约

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M3、[ADR 0004](0004-eval-contracts-and-deps.md)

## 背景

M3 要交付 `agentlens eval run --gate`：CI 里跑一次评测，不达标就非零退出。两个问题要先定：
CLI 自己带一套评测 Runner，还是复用平台的引擎；退出码怎么定义 CI 才好消费。

## 决策

1. **CLI 是平台的薄客户端，不复制评测引擎**：`agentlens eval run` 调 `POST /api/eval-runs`
   启动评测，轮询 `GET /api/eval-runs/{id}` 直到 running 结束，再按 `totals.passRate` 对照
   `--gate` 阈值给出退出码。数据集、评分、成本归因只有一份实现（server），CLI 的依赖只有
   Jackson 与 JDK HttpClient，无 Spring。
2. **退出码三态**：0 = run completed 且达标（或不带 `--gate`）；1 = run completed 但未达标；
   2 = 用法错误 / 平台不可达 / run failed / 轮询超时 / 空数据集。1 与 2 分开，CI 能区分
   "质量门禁没过"和"评测本身没跑起来"。
3. **门禁指标只有 passRate**。成本/延迟门禁暂不做——对比 API 已暴露这两项，等真实使用
   再决定形态。
4. **`--gate` 必须搭配 `--scorers`**：没有评分器的 run 在服务端逐例默认通过，门禁形同
   虚设，直接拒绝启动。
5. **空 case 的数据集按操作失败处理（退出码 2）**：不静默通过，也不算门禁失败。
6. **分发用 maven-shade 打可执行 fat jar**（`java -jar agentlens.jar`，最终名 `agentlens`）。
   平台侧的单二进制仍由 server 承担（ADR 0002），CLI 是配套工具，不是第二个平台。

## 后果

- 正：评测语义零漂移（引擎只有一份）；CI 接入只是"启动评测 + 看结果"两条 HTTP 调用的
  语义化封装
- 负：CLI 依赖平台在线；离线直跑本地函数的形态继续延后（ADR 0004 已记）
- 中性：轮询间隔 1s、默认超时 600s，对 CI 足够；`agentlens.eval.*` 属性埋点仍由
  Agent 侧负责（示例与 sdk-java 提供）
