<p align="center">
  <img src="docs/brand/weaver-logo.png" width="180" alt="Weaver logo">
</p>

# Weaver

**将模型、工具与上下文编织成行动。** 一个基于 Java 的 AI Agent 运行时框架。

English | [简体中文](README.zh-CN.md)

> 早期开发中——本项目从零开始逐步构建，路线图见下。

## 项目定位

- 自研 Agent 核心（主循环、状态机、事件溯源）——不依赖 LangChain4j / Spring AI
- 通过官方 Java SDK 接入 MCP 生态
- 可插拔工具体系：Java SPI、Node 边车桥接、MCP 三种形态
- Spring Boot 作为外围壳，承载 Web 界面与会话持久化

## 模块

| 模块 | 职责 |
|---|---|
| `weaver-core` | 自研核心引擎（ReAct 策略链、状态机、事件流），不依赖 Spring 或 Agent 框架 |
| `weaver-plugins` | 插件契约：SPI / Node 桥接 / MCP 工具形态 |
| `weaver-mcp` | MCP 客户端接入（官方 Java SDK） |
| `weaver-server` | Spring Boot 应用：REST + SSE、SQLite 事件存储、OpenAI 兼容与 Anthropic 模型客户端 |
| `weaver-cli` | 命令行入口（核心之上的薄壳） |
| `weaver-ui` | React 控制台：会话、审批、插件与设置（骨架） |

## 开发

```bash
mvn -q compile        # 编译全部模块
mvn -q test           # 运行测试
mvn -q verify         # 测试 + JaCoCo 分支覆盖率门槛

cd weaver-ui
npm install
npm run dev           # 控制台 http://localhost:5173
```

AI 协作规范见 [AGENTS.md](AGENTS.md)。

## 许可证

待定（计划 MIT）。