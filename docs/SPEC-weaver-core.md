# Spec: Weaver Core (M1)

[简体中文](SPEC-weaver-core.zh-CN.md) | English

## Objective

Deliver the M1 foundation: a working agent coding loop with a Web demo covering six views — streaming reasoning, tool-call cards, approval popups, diff preview, event replay, multi-session management.

User story: I open Weaver, create a session, and ask the agent to modify code in a workspace. I watch the model stream its reasoning, see each tool call as a card (file read / shell command / git op, with timing), get a confirmation popup before any dangerous command, review a git diff, approve or reject the change, and commit. Later I can reopen any session and replay it step by step; several sessions exist side-by-side without mixing data.

Out of scope (later milestones): MCP integration and SKILL.md loading, context compaction, subagents, plugin contract in three shapes, MySQL/higher-scale backends beyond the optional driver, CLI entrypoint.

## Tech Stack

- Java 21 — `weaver-core` is **zero-framework** (JDK + Jackson only).
- Spring Boot 3.5.3 (server shell only), Spring Web (REST + `SseEmitter`).
- Persistence: `EventStore` interface in core; SQLite default implementation (JDBC); MyBatis + MySQL as an optional implementation.
- Frontend: React + Vite (HTTP + SSE; no GraphQL).
- Tests: JUnit 5; server integration via Spring's test slice.

## Commands

```bash
mvn -q compile          # build all modules
mvn -q test             # run tests
mvn -q -pl weaver-server spring-boot:run   # run server (SQLite, zero setup)
cd weaver-ui && npm install && npm run dev # run UI
```

## Project Structure

```
weaver-core/src/main/java/io/github/hjyao/weaver/core/
  loop/       # RunLoop, Turn, StrategyHandler, chain nodes (Resolve/Intent/Dispatch/Collect)
  phase/      # sealed Phase (Idle/Running/Maintenance), abort, wake
  events/     # sealed SessionEvent, seq, SessionEventStore interface
  policy/     # ApprovalPolicy, allow/deny rules table
  tool/       # Tool interface, built-ins: FileTool, ShellTool, GitTool
  model/      # ModelClient SPI, StreamChunk types
weaver-server/src/main/java/.../server/
  api/        # sessions, runs, approvals, replay endpoints
  sse/        # stream relay
  store/      # SqliteEventStore, MyBatisEventStore (optional)
  config/     # model settings (.env defaults, runtime overrides)
weaver-ui/src/  # chat view, tool cards, approval modal, diff view, replay player, session list
docs/        # specs, ADRs
```

## Architecture

Modeled on Pi (packages/agent as the loop core) and Codex (codex-rs crate split):

1. **One capability, one package** — like Pi's `packages/{agent,ai,durable,mcp,tui}`: loops live in `loop/`, the model boundary in `model/`, persistence *interfaces* in `events/`, execution in `tool/`. An entry module aggregates (weaver-server / weaver-cli), the core never self-references back.
2. **Straight-line core + events as the side-track bus** — the run loop reads top-to-bottom in one place (`RunLoop`), exactly like Pi's single `agent-loop.ts`; everything the loop *emits* is a `SessionEvent`. Things that react to emissions (persist, emit SSE, replay) subscribe to events instead of being called by the loop — no deep call chains.
3. **Two extension buses** — hooks/chain nodes (`StrategyHandler` slots: Resolve/Intent/Dispatch/Collect + `finishTurn`-style decisions, borrowed from Pi) change *behavior*; events change/observe *state*. New capability = new node/registration or new event handler, never a loop edit.

## Code Style

The bloat rules from the postmortem are contract, not advice:

1. **Small methods/classes (guideline, not hard limit)**: aim for methods ≤ 40 lines and classes ≤ 250 lines; when a piece genuinely reads better as one longer flow, keep it — but extraction must reduce, not hide, complexity.
2. **Delegation depth (review flag, not a hard cap)**: prefer straight-line flow; when a delegation chain grows past ~3 hops (A → B → C → D), stop and ask whether the middleman should be replaced by an event, a composed object, or an inlined step — the way Pi keeps its loop in one straight file and moves side-tracks out via events and hook callbacks.
3. **Straight-line loop logic**: the run loop reads like a table — prepare turn → call model → execute tools → decide finish. Branching via early returns, not nested if/else pyramids.
4. **One-directional deps**: `weaver-core` imports nothing from other modules; packages depend only on interfaces, never implementations.
5. **Naming**: domain words (`RunLoop`, `Turn`, `Step`, `SessionEvent`). Generic `Manager`/`Util` suffixes are banned; `StrategyHandler` is kept (it is the chain contract name).
6. **Comments (modeled on Pi / Codex / Claude Code)**:
   - Comments live mostly at **public boundaries** (interfaces, sealed types, public methods): one or two sentences of *what* plus *contract semantics* — invariants, "must not throw", "missing X is a no-op", the same style Pi's hook docs and Codex's `AgentGraphStore` trait docs use.
   - Inside implementations, comment only **why & invariants**. Never comment "what" — names say what. No decoration comments, no author/date noise, no `TODO` without an issue link.
   - Comments are written in **Chinese** (the maintainer's primary language); bilingual EN/ZH is welcome where a comment may later become public documentation. Terse and precise; `@param`/`@return` only when not obvious from the signature and name.

## Testing Strategy

- JUnit 5; `weaver-core` branch coverage ≥ 80% (JaCoCo gate).
- Unit: every `SessionEvent` variant folds correctly; replay from seq 0 reconstructs state; approval table is table-driven (allow/deny cases); step cap and abort paths.
- Integration (server): SSE stream delivers chunks; approval round-trip pauses the run until answered; session isolation under two concurrent sessions.
- UI: mocked-API component tests for the six views; no UI tests assert backend logic.

## Boundaries

- **Always**: TDD for core logic; `mvn -q test` green before commit; documents in EN/ZH pairs; event seq monotonic; replay must reproduce state.
- **Ask first**: DB schema changes, new dependencies, approval-rule changes, concurrency-model changes, anything that touches `RunLoop`'s contract.
- **Never**: commit secrets; `weaver-core` importing Spring; breaking event backward compatibility; adding delegation hops just to "organize"; speculative abstractions without a second consumer.

## Success Criteria (M1 acceptance)

1. Streaming: first chunk visible in the UI; tool cards show tool name, args summary, duration.
2. Approval: `rm`, `git commit/push`, installs → popup; in-workspace reads / `git status/diff` → auto-allowed.
3. Diff: after an edit run, UI shows a diff view; approve → commit applied.
4. Replay: any finished session replays from seq 0; folded state equals live state.
5. Sessions: creating/opening/switching multiple sessions never mixes events.
6. Simplicity: code review passes the depth/line rules above; `weaver-core` has zero Spring references (verified by a test).

## Open Questions

None — resolved in the interview (scene A, six views, single-user, SQLite-default + MyBatis/MySQL optional, whitelist approval, .env + settings-page model config).