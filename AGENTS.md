# AGENTS.md — Weaver 协作规范

适用于在本仓库工作的所有 AI Agent 与人类协作者。

## 1. 项目定位

Weaver 是一个从零手写的 Java Agent 框架：**核心自研，Spring 只做外围壳子**。

## 2. 架构基线

借鉴 Pi / Codex / Claude Code，按 Weaver 的边界裁剪。**这是设计决策，不是建议**：

| 决策 | 选择 | 理由 |
|---|---|---|
| 循环结构 | 直线 `RunLoop` + 单步策略链 | 循环只管“走多少步”，链只管“这一步怎么走”，职责分离 |
| 扩展机制 | `StrategyHandler` + `AbstractStrategyRouter`：`doApply` 返回 null 续链、非 null 终止；`getNext` 按上下文路由 | 新增能力 = 新增节点 + 注册进链，核心循环零改动 |
| 状态推进 | 消息信箱（inbox）+ sealed `Phase` | 异步可恢复；状态迁移在编译期封闭 |
| 流式 | `java.util.concurrent.Flow.Publisher<StreamChunk>` | JDK 原生背压，不引入响应式框架 |
| 事实源 | seq 单调的 `SessionEvent` 事件流 | 回放 == 现场；投影缓存按 lastSeq 失效 |
| 模型 / 工具边界 | `ModelClient` / `Tool` SPI | 核心不绑定具体厂商或执行环境 |

**不采用**：通用工作流引擎（BPMN / 状态图引擎）、高层 Agent 框架（LangChain / Spring AI / ADK）、为整齐而叠的动态代理层。策略树只服务 Agent 循环内的单步编排，不扩张为通用任务编排器。

## 3. 模块边界（铁律）

| # | 规则 |
|---|---|
| 1 | `weaver-core` **不依赖 Agent 框架、DI 容器、Web / ORM 框架**（Spring、Spring AI、LangChain4j、Google ADK 等一律禁止）。允许纯工具库（Jackson、SLF4J API、JUnit）；新增运行时依赖需在 PR 说明理由 |
| 2 | 依赖方向单向：`core ← plugins ← mcp ← server/cli`。上层可依赖下层，反向禁止 |
| 3 | `weaver-server` 是唯一允许使用 Spring 的模块 |

## 4. 技术基线

- Java 21（鼓励虚拟线程、sealed interface、record）；编译器 `release=21`
- 包名统一：`cn.yhjyhj.weaver.<module>`（反向域名，源于 yhjyhj.cn）
- 构建：Maven 多模块，父 POM 已导入 Spring Boot BOM（依赖版本统一管理）
- 测试：JUnit 5；核心逻辑必须 TDD（先写测试再实现）
- 本机构建：`JAVA_HOME` 必须指向 JDK 21+（如 `~\.jdks\corretto-24.0.2`）

## 5. 代码风格

1. **标识符**（类/方法/变量/包）：英文驼峰；**注释**：中文（可能成为公开文档的可中英双语）
2. 方法与类的体量为**指引而非硬限**（方法尽量 ≤40 行、类尽量 ≤250 行）；逻辑整体成流更清晰时允许超长，但抽方法必须降低复杂度
3. 主流程**直线式**书写，支线用事件/钩子外移；委托链过长是评审红灯
4. 命名用领域词（`RunLoop`、`Turn`、`Step`、`SessionEvent`）；禁止 `Manager`/`Util` 等泛词；`StrategyHandler` 是链契约名，保留

## 6. 文档语言政策

| 读者 | 语言 |
|---|---|
| 只给 Agent 看的内部过程文档（规格/计划/任务，放 `local/`） | 中文（默认）或英文 |
| 只给开发人员看的公开文档（如 `docs/adr/`） | 中文 |
| 既给 Agent 也给开发人员（如本文件） | 中文 |
| 对外门面（README、ROADMAP、LICENSE） | 中英双语成对 |

- 过程文档（规格、计划、任务、复盘）一律放 `local/docs/`、`local/tasks/`，**永不提交**（对齐 Pi / Codex / Claude Code 的做法）
- `local/` 绝对不提交（参考源码、过程文档、草稿、密钥）

## 7. Git 规范

- `main` 受保护：只经 PR 合入，不直推（M0 引导期提交除外）
- 分支命名：`feat/` `fix/` `docs/` `refactor/` `chore/`；**开工前先在计划里写清本分支目标**（`local/tasks/`）
- 一分支一目标；PR 说明含 what / why / how-verified；squash 合入，保持 main 历史一条一变更
- 发版：main 上打 `v<major>.<minor>.<patch>` tag 并写 changelog

## 8. 质量红线（代码必须真实做到）

- 会话可审计、可回放：事件 seq 单调；**回放 == 现场**（有测试锁死）
- 核心循环对修改封闭：新增能力 = 新增节点 + 注册进链（或注册钩子 / 事件处理器），绝不改动循环本身
- 单轮步数上限、取消机制、流式输出必须有测试覆盖
