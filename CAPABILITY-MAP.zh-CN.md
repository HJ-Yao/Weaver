# 能力地图

English | [简体中文](CAPABILITY-MAP.zh-CN.md)

| 模块 id | 职责 | 依赖 |
|---|---|---|
| `weaver-core` | 自研引擎：策略链、Phase 状态机、事件流、审批策略、工具抽象、模型客户端 SPI | — |
| `weaver-plugins` | 三形态插件契约：Java SPI / Node 桥接 / MCP | `weaver-core` |
| `weaver-mcp` | MCP 客户端接入（官方 Java SDK） | `weaver-plugins` |
| `weaver-server` | Spring Boot 外壳：REST + SSE、会话生命周期、EventStore 实现（SQLite 默认，MyBatis/MySQL 可选）、模型配置 | `weaver-core`, `weaver-plugins` |
| `weaver-cli` | 命令行入口（薄壳） | `weaver-core`, `weaver-plugins` |
| `weaver-ui` | React 聊天界面（M1 六画面） | `weaver-server`（HTTP/SSE） |

构建顺序：`weaver-core` → `weaver-server` → `weaver-ui`（M1）；`weaver-plugins`、`weaver-mcp`（M2–M3）；`weaver-cli`（M4）。

接口定义在模块边界：`EventStore`、`ModelClient`、`Tool`、`ApprovalPolicy` 均定义于 `weaver-core`，由其他模块实现或消费。