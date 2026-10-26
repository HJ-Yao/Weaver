# Weaver

**Weave models, tools and context into action.** A Java-based AI agent harness.
**将模型、工具与上下文编织成行动。** 一个基于 Java 的 AI Agent 运行时框架。

> Early WIP — this project is developed incrementally from scratch. See the roadmap below.
> 早期开发中——本项目从零开始逐步构建，路线图见下。

## What it is / 项目定位

- Hand-written agent core (loop, state machine, event sourcing) — no LangChain4j / Spring AI
- 自研 Agent 核心（主循环、状态机、事件溯源）——不依赖 LangChain4j / Spring AI
- MCP support via the official Java SDK
- 通过官方 Java SDK 接入 MCP 生态
- Pluggable tools: Java SPI, Node sidecar bridge, MCP
- 可插拔工具体系：Java SPI、Node 边车桥接、MCP 三种形态
- Spring Boot shell for the Web UI and session persistence
- Spring Boot 作为外围壳，承载 Web 界面与会话持久化

## Modules / 模块

| Module | Role |
|---|---|
| `weaver-core` | Hand-written agent engine (strategy chain, state machine, event stream). Zero framework deps. / 自研核心引擎，零框架依赖 |
| `weaver-plugins` | Plugin contract: SPI / Node bridge / MCP. / 插件契约 |
| `weaver-mcp` | MCP client integration. / MCP 客户端接入 |
| `weaver-server` | Spring Boot app: REST API, SSE streaming, MySQL. / Web 服务端 |
| `weaver-cli` | Command-line entrypoint. / 命令行入口 |
| `weaver-ui` | React frontend (chat UI). / React 前端聊天界面 |

## Development / 开发

```bash
mvn -q compile        # build all Java modules / 编译全部模块
mvn -q test           # run tests / 运行测试
```

Rules for AI agents working on this repo: [AGENTS.md](AGENTS.md).
AI 协作规范见 [AGENTS.md](AGENTS.md)。

## License

TBD (MIT planned).