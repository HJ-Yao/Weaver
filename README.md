<p align="center">
  <img src="docs/brand/weaver-logo.png" width="180" alt="Weaver logo">
</p>

# Weaver

**Weave models, tools and context into action.** A Java-based AI agent harness.

[简体中文](README.zh-CN.md) | English

> Early WIP — this project is developed incrementally from scratch. See the roadmap below.

## What it is

- Hand-written agent core (loop, state machine, event sourcing) — no LangChain4j / Spring AI
- MCP support via the official Java SDK
- Pluggable tools: Java SPI, Node sidecar bridge, MCP
- Spring Boot shell for the Web UI and session persistence

## Modules

| Module | Role |
|---|---|
| `weaver-core` | Hand-written agent engine (ReAct strategy chain, state machine, event stream). No Spring or agent-framework dependencies. |
| `weaver-plugins` | Plugin contract: SPI / Node bridge / MCP tool shapes. |
| `weaver-mcp` | MCP client integration (official Java SDK). |
| `weaver-server` | Spring Boot app: REST + SSE, SQLite event store, OpenAI-compatible and Anthropic model clients. |
| `weaver-cli` | Command-line entrypoint (thin shell over the core). |
| `weaver-ui` | React console: session chat, approvals, plugins and settings (scaffold). |

## Development

```bash
mvn -q compile        # build all Java modules
mvn -q test           # run tests
mvn -q verify         # tests + JaCoCo branch coverage gate

cd weaver-ui
npm install
npm run dev           # web console on http://localhost:5173
```

Rules for AI agents working on this repo: [AGENTS.md](AGENTS.md).

## License

TBD (MIT planned).