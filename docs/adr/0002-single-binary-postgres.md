# ADR 0002 — 单二进制分发，PostgreSQL 存储

- 状态：已接受（2026-09-27）
- 关联：[PLAN.md](../PLAN.md) M1

## 背景

同类工具的痛点：SaaS 型数据出域不可接受；自托管型（如 Langfuse）要拉起多个组件，运维成本高。

## 决策

1. 分发形态：**单二进制**（一个 Spring Boot 可执行 jar），`docker compose up` 一条命令带起 jar + PostgreSQL。
2. 存储用 **PostgreSQL**：MVP 不引入 ClickHouse 等专用分析库；span 表按时间分区，够用再演进。
3. 前端随 jar 内嵌静态资源，不独立部署。

## 后果

- 正：部署面 = 一个 compose 文件；升级 = 换 jar；小团队十分钟可用
- 负：海量 span 场景下 PostgreSQL 早晚是瓶颈，届时以 ADR 形式引入列存后端，存储层从 M1 起就隔离成接口
- 中性：单机部署默认不解决多租户与鉴权体系，v0.x 只做单用户 + token 鉴权
