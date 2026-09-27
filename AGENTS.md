# agent-lens — 开发约定

单二进制、自托管的 Agent 可观测性与评测平台。整体规划见 `docs/PLAN.md`，架构决策见 `docs/adr/`（改动前先读，改完架构要补 ADR）。

## 技术栈

- `server/`：Java + Spring Boot 3.x，构建用 Maven
- 具体版本（JDK / Boot / 依赖）开工时锁定为当时最新 GA，并记入 ADR 后不再随意升级

## 模块

- `server/` — 采集（OTLP 端点）、存储（PostgreSQL）、API、评测引擎
- `sdk-java/` — 薄客户端 SDK，只封装 OTLP 上报，不做协议扩展
- `cli/` — `agentlens` 命令行：评测运行器 + CI 门禁（非零退出码）

## 硬约定

1. **数据契约只跟标准走**：span 属性用 OTel GenAI 语义约定（`gen_ai.*`），不自造属性名；确需扩展时加 `agentlens.*` 前缀并写 ADR
2. **commit 信息只写"改了什么、为什么"**，遵循 Conventional Commits（`feat:` / `fix:` / `docs:` / `chore:`）
3. **文档分工**：`README.md` 英文（面向访客），`docs/` 中文（面向开发）
4. **每个可运行模块带测试**；`server/` 的接口必须有集成测试
5. **配置不进仓库**：密钥、连接串走环境变量 / `.env`（已 gitignore）

## 构建与运行

```bash
# 需 JDK 25（版本锁定见 docs/adr/0003）
docker compose up -d postgres && ./mvnw spring-boot:run -pl server
# 看板：http://localhost:8080 ；示例：./mvnw -pl examples/minimal-agent compile exec:java
```
