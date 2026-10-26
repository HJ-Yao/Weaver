# AGENTS.md — Collaboration rules

Rules for any AI agent working in this repository. English only, on purpose: this file is read by agents, not by visitors. Public-facing docs have separate EN/ZH versions.

## Positioning

Weaver is a from-scratch Java agent harness: the core is hand-written, Spring only wraps the outside.

## Module boundaries (hard rules)

1. `weaver-core` must have **zero framework dependencies** — JDK only (a pure utility lib like a JSON codec needs PR justification). Importing any `org.springframework.*` is forbidden.
2. Dependency direction is one-way: core ← plugins ← mcp ← server/cli. Upper layers may depend on lower layers, never the reverse.
3. `weaver-server` is the only module allowed to use Spring.

## Baseline

- Java 21 (virtual threads, sealed interfaces, records welcome)
- Build: Maven multi-module; the parent POM already imports the Spring Boot BOM, so dependency versions are managed.
- Tests: JUnit 5; core logic must follow TDD (test first, then implementation).

## Discipline

1. Commit per small feature; Conventional Commits (feat/fix/refactor/docs/test).
2. `mvn -q test` must pass before merging.
3. `local/` is never committed (reference sources, drafts, secrets).
4. Public docs: separate EN/ZH versions with cross-links (README.md + README.zh-CN.md, etc.). Keep this file English-only.
5. Architecture decisions go into `docs/adr/` as ADRs.

## Quality lines this project actually enforces

- Sessions are auditable and replayable: monotonic event seq, state computed by folding/replaying events.
- The core loop is closed for modification: adding a capability = adding a node + registering it, never touching the loop itself.
- Per-turn step cap, cancellation, and streaming output must be covered by tests.