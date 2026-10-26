# AGENTS.md — Collaboration rules / 协作规范

Rules for any AI agent working in this repository (bilingual for humans and agents alike).
适用于在本仓库工作的任何 AI Agent 的规则。

## Positioning / 一句话定位

Weaver is a from-scratch Java agent harness: the core is hand-written, Spring only wraps the outside.
Weaver 是一个从零手写的 Java Agent 框架：核心自研，Spring 只做外围壳子。

## Module boundaries (hard rules) / 模块边界（铁律）

1. `weaver-core` must have **zero framework dependencies** — JDK only (a pure utility lib like a JSON codec needs PR justification). Importing any `org.springframework.*` is forbidden.
   `weaver-core` 必须**零框架依赖**——仅 JDK（引入 JSON 编解码等纯工具库需在 PR 中说明理由）。禁止 import 任何 `org.springframework.*`。
2. Dependency direction is one-way: core ← plugins ← mcp ← server/cli. Upper layers may depend on lower layers, never the reverse.
   依赖方向单向：core ← plugins ← mcp ← server/cli。上层可依赖下层，反向禁止。
3. `weaver-server` is the only module allowed to use Spring.
   `weaver-server` 是唯一允许使用 Spring 的模块。

## Baseline / 技术基线

- Java 21 (virtual threads, sealed interfaces, records welcome) / Java 21（鼓励虚拟线程、sealed interface、record）
- Build: Maven multi-module; the parent POM already imports the Spring Boot BOM, so dependency versions are managed.
  构建：Maven 多模块；父 POM 已导入 Spring Boot BOM，依赖版本统一管理。
- Tests: JUnit 5; core logic must follow TDD (test first, then implementation).
  测试：JUnit 5；核心逻辑必须 TDD（先写测试再实现）。

## Discipline / 开发纪律

1. Commit per small feature; Conventional Commits (feat/fix/refactor/docs/test).
   每个小功能点独立提交；commit message 用 Conventional Commits。
2. `mvn -q test` must pass before merging. / 合并前必须通过 `mvn -q test`。
3. `local/` is never committed (reference sources, drafts, secrets). / `local/` 绝对不提交（参考源码、草稿、密钥）。
4. Docs are bilingual (English + Chinese). / 文档一律中英双语。
5. Architecture decisions go into `docs/adr/` as ADRs. / 架构决策写入 `docs/adr/` 的 ADR。

## Quality lines this project actually enforces / 质量红线（代码必须真实做到）

- Sessions are auditable and replayable: monotonic event seq, state computed by folding/replaying events.
  会话可审计、可回放：事件 seq 单调，状态由事件折叠重放计算。
- The core loop is closed for modification: adding a capability = adding a node + registering it, never touching the loop itself.
  核心循环对修改封闭：新增能力 = 新增节点 + 注册，绝不改动循环本身。
- Per-turn step cap, cancellation, and streaming output must be covered by tests.
  单轮步数上限、取消机制、流式输出必须有测试覆盖。