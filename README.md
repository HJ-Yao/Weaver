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

### Running the server from `.env`

The server reads `weaver.*` properties. Put them in a `.env` file at the
repository root — it is git-ignored, so credentials never reach the repository:

```properties
weaver.workspace=${user.dir}/..
weaver.max-steps=20
weaver.store-path=${user.dir}/../local/dev/sessions.db
weaver.model.protocol=openai-chat-completions
weaver.model.base-url=https://api.deepseek.com
weaver.model.model=deepseek-chat
weaver.model.api-key=sk-...
```

Then start it from the server module:

```bash
cd weaver-server
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:../.env[.properties]"
```

`${user.dir}` is the launch directory (`weaver-server/`), so `..` resolves to
the repository root. The `optional:` prefix keeps the command working before
the file exists; without a `.env` every property must be passed as `--weaver.*`
on the command line.

Rules for AI agents working on this repo: [AGENTS.md](AGENTS.md).

## License

TBD (MIT planned).