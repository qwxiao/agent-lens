# ADR 0003 — 技术栈版本锁定（M1 开工）

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M1、AGENTS.md 构建约定

## 背景

AGENTS.md 约定：开工时把 JDK / Boot / 依赖锁定为当时最新 GA，记入 ADR 后不再随意升级。M1 开工时点的版本状况：

- **Spring Boot**：3.x 线的最后一个开源版本是 **3.5.16**（2026-06-25 发布）。3.5 线的开源支持已于 2026-06-30 结束，4.0.x 是当前受支持的线。仓库约定钉在 3.x，因此取 3.5.16。
- **JDK**：当前最新 GA 是 **25**（LTS，2025-09 发布）。Spring Framework 6.2.x（Boot 3.5 所用）官方支持 Java 17–25。
- **PostgreSQL**：compose 镜像取 **18-alpine**（2025-09 GA，Flyway 11 与 JDBC 驱动均已支持）。
- **Flyway / Testcontainers / Jackson / snakeyaml**：全部随 `spring-boot-starter-parent` BOM 管理，不单独锁版本。

## 决策

| 项 | 锁定版本 | 说明 |
|---|---|---|
| JDK | 25（Temurin） | 编译目标 `release=25`，运行需 JDK 25+ |
| Spring Boot | 3.5.16 | 3.x 线最终 OSS 版本 |
| PostgreSQL | 18-alpine | compose 与集成测试统一 |
| Maven | Wrapper（3.9.16） | 仓库自带 `./mvnw`，不依赖本机安装 |
| 其余依赖 | Boot BOM 管理 | 不在 pom 里手写版本号 |

## 后果

- 正：版本全部可复现，升级是显式 ADR 决策；JDK 25 LTS 支撑到 2030，短期内无升级压力
- 负：Boot 3.5.16 不再有开源安全补丁；若出现 CVE 或要跟进 4.x 新特性，需另立 ADR 升级（3.5 → 4 有 Vaadin 等公开迁移手册可循）
- 中性：JDK 25 意味着贡献者本地也要 JDK 25+，README 会写明；虚拟线程等新特性按需使用，不为此专门重构
