# Capability Map / 能力地图

[简体中文](CAPABILITY-MAP.zh-CN.md) | English

| Module id | Responsibility | Depends on |
|---|---|---|
| `weaver-core` | Hand-written engine: strategy chain, Phase state machine, event stream, approval policy, tool abstraction, model client SPI | — |
| `weaver-plugins` | Plugin contract in three shapes: Java SPI / Node bridge / MCP | `weaver-core` |
| `weaver-mcp` | MCP client integration (official Java SDK) | `weaver-plugins` |
| `weaver-server` | Spring Boot shell: REST + SSE, session lifecycle, EventStore implementations (SQLite default, MyBatis/MySQL optional), model config | `weaver-core`, `weaver-plugins` |
| `weaver-cli` | Command-line entrypoint (thin shell) | `weaver-core`, `weaver-plugins` |
| `weaver-ui` | React chat UI (six views of M1) | `weaver-server` (HTTP/SSE) |

Build order: `weaver-core` → `weaver-server` → `weaver-ui` (M1); `weaver-plugins`, `weaver-mcp` (M2–M3); `weaver-cli` (M4).

Interfaces live at module boundaries: `EventStore`, `ModelClient`, `Tool` and `ApprovalPolicy` are defined in `weaver-core`; other modules implement or consume them.