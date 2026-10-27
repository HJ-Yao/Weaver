# AGENTS.md — 协作规范

适用于在本仓库工作的任何 AI Agent 与协作者。

## 定位

Weaver 是一个从零手写的 Java Agent 框架：核心自研，Spring 只做外围壳子。

## 模块边界（铁律）

1. `weaver-core` **零框架依赖**——仅 JDK（引入 JSON 编解码等纯工具库需在 PR 中说明理由）。禁止 import 任何 `org.springframework.*`。
2. 依赖方向单向：core ← plugins ← mcp ← server/cli。上层可依赖下层，反向禁止。
3. `weaver-server` 是唯一允许使用 Spring 的模块。

## 技术基线

- Java 21（虚拟线程、sealed interface、record）
- 构建：Maven 多模块，父 pom 已导入 Spring Boot BOM
- 测试：JUnit 5，核心逻辑 TDD（先测试后实现）

## 文档语言政策

| 读者 | 语言 |
|---|---|
| 只给 Agent 看（过程文档：规格/计划/任务，放 `local/`） | 中文（默认）或英文 |
| 只给开发人员看（公开 docs/，如 ADR） | 中文 |
| 既给 Agent 也给开发人员（如 AGENTS.md） | 中文 |
| 对外门面（README、ROADMAP、LICENSE 等） | 中英双语成对 |

参考三大 Agent 仓库（Pi / Codex / Claude Code）的做法：**不提交** spec/plan/tasks 这类过程文档。Weaver 的过程文档一律放 `local/docs/`、`local/tasks/`，永不提交。公开仓库只保留：README/ROADMAP（双语门面）、AGENTS.md（开源惯例，Pi 也提交）、LICENSE、CHANGELOG。

## 开发纪律

1. 每个小功能点独立提交；Conventional Commits（feat/fix/refactor/docs/test）。
2. 合并前 `mvn -q test` 必须全绿。
3. `local/` 绝对不提交（参考源码、过程文档、草稿、密钥）。
4. 代码注释用中文（维护者主力语言）；可变公开的注释可中英双语。
5. 架构决策写入 `docs/adr/`（公开，中文）。

## Git 规范（企业模式）

- `main` 受保护：只经 PR 合入，不直推（M0 引导期提交除外）。
- 分支命名：`feat/` `fix/` `docs/` `refactor/` `chore/`；开工前先在 `local/tasks/` 写清本分支目标。
- 一分支一目标（对应 `local/tasks/todo.md`），PR 合入后勾掉对应任务。
- PR squash 合并，保持 main 历史一条一变更；PR 说明需含 what / why / how-verified。
- 发版：main 上打 `v<major>.<minor>.<patch>` tag 并在 release notes 写 changelog 条目。

## 质量红线（代码必须真实做到）

- 会话可审计、可回放：事件 seq 单调，状态由事件折叠重放计算。
- 核心循环对修改封闭：新增能力 = 新增节点 + 注册，绝不改动循环本身。
- 单轮步数上限、取消机制、流式输出必须有测试覆盖。
- 方法与类的体量是"尽力而为"指引；委托链过长是评审红灯（参照 Pi 的做法：直线主流程 + 事件/钩子外移支线）。