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

## 5. 包组织规范

参考 Codex 的「按职责分目录 + 根级入口」，**不按类型平铺**：

| # | 规则 |
|---|---|
| 1 | 包根只放**入口 / 门面类**（如 `RunLoop`）；契约、实现、数据载体分到子包 |
| 2 | 按**职责**分包，包名用领域词：`chain`（契约与模板）、`node`（阶段实现）、`context`（请求 / 上下文 / 结果）、`memory` 或 `jdbc`（存储实现） |
| 3 | 单个包的直接类 **≤ 8 个**；超过时按子职责再分 |
| 4 | 禁止 `utils`、`common`、`helper` 这类大杂烩包。通用能力必须落到有领域含义的包 |
| 5 | 包间依赖单向，禁止循环；数据包（`context`）不反向依赖执行包（`node`）；**装配入口（工厂）放包根**，避免 `chain` 与 `node` 互相依赖 |
| 6 | 一个类只做一件事；`XxxManager`、`XxxUtil`、`XxxHelper` 是评审红灯 |

当前 `loop` 包的标准结构：

```
loop/
  RunLoop.java            运行入口
  RunChainFactory.java    装配入口（依赖 chain + node + context）
  chain/                  StrategyHandler、AbstractStrategyRouter、AbstractRunNode
  node/                   ResolveNode、IntentNode、DispatchNode、CollectNode
  context/                RunRequest、RunContext、StepResult、EventSink
```

## 6. 注释规范

参考《阿里巴巴 Java 开发手册》与 Pi / Codex 源码：**公开边界写契约，方法内部写理由**。

| # | 规则 |
|---|---|
| 1 | 所有 `public` 类 / 接口 / record / enum / 公开业务方法必须有 `/** */` Javadoc；说明**做什么、契约、不变量、边界**，不写实现步骤。简单 getter / setter 除外 |
| 2 | **所有字段必须有注释**：简单字段用行尾 `//`，带约束的字段用 `/** */` |
| 3 | 枚举常量逐个注释 |
| 4 | 方法内部关键步骤用 `//`，解释**为什么**或**不变量**；不复述代码字面意思 |
| 5 | 不注释显而易见的代码（getter / setter、简单赋值、单一 return） |
| 6 | 注释用中文；可能进入公开文档的用中英双语 |
| 7 | 禁止：装饰性分隔线、作者 / 日期噪音、自动生成注释、复述方法名的废话 |

示例：

```java
/** 本次模型调用的输入与历史快照。 */
public record ModelRequest(String input, List<SessionSnapshot.Message> history) {

    /** 输入文本。 */
    private final String input;   // 字段行尾注释也可以
}
```

## 7. TODO 规范

- 未实现或后续里程碑要做的事，必须写 `// TODO(M2): 具体事项`，**带里程碑编号**
- 不允许 `// TODO` 后面没有上下文，也不允许“以后优化”这类空话
- 事项完成时删除对应 TODO
- 当前已知的后续项：`TODO(M2)` inbox 异步推进、max_tokens 续写；`TODO(M3)` 投影缓存、压缩溯源

## 8. 文档语言政策

| 读者 | 语言 |
|---|---|
| 只给 Agent 看的内部过程文档（规格/计划/任务，放 `local/`） | 中文（默认）或英文 |
| 只给开发人员看的公开文档（如 `docs/adr/`） | 中文 |
| 既给 Agent 也给开发人员（如本文件） | 中文 |
| 对外门面（README、ROADMAP、LICENSE） | 中英双语成对 |

- 过程文档（规格、计划、任务、复盘）一律放 `local/docs/`、`local/tasks/`，**永不提交**
- `local/` 绝对不提交（参考源码、过程文档、草稿、密钥）

## 9. Git 规范

- `main` 受保护：只经 PR 合入，不直推（M0 引导期提交除外）
- 分支命名：`feat/` `fix/` `docs/` `refactor/` `chore/`；**开工前先在计划里写清本分支目标**（`local/tasks/`）
- 一分支一目标；PR 说明含 what / why / how-verified；squash 合入，保持 main 历史一条一变更
- 发版：main 上打 `v<major>.<minor>.<patch>` tag 并写 changelog

## 10. Agent 工具使用

给 Codex 或其他执行型 Agent 派任务时，**任务卡必须写明可用工具及用途**：

| 场景 | 工具 |
|---|---|
| 新功能 / 修 bug | `test-driven-development` skill（先红后绿） |
| 提交前自查 | `code-review-and-quality` skill |
| 重构 / 简化 | `code-simplification` skill |
| 第三方评审 | `code-review-mcp`（Codex + Gemini）、`critic-mcp` |
| Git 只读状态 | `agentic-git` |
| 终端 / 构建 | `agentic-shell` |

- 只读优先；写操作必须符合本文件第 3、9 节
- **禁止使用指向其他项目的 MCP**（当前 `agentic-filesystem` 指向 `hotel_project`，对 Weaver 无效）
- 不要为了用工具而用工具；任务卡没写的不必强行调用

## 11. 质量红线（代码必须真实做到）

- 会话可审计、可回放：事件 seq 单调；**回放 == 现场**（有测试锁死）
- 核心循环对修改封闭：新增能力 = 新增节点 + 注册进链（或注册钩子 / 事件处理器），绝不改动循环本身
- 单轮步数上限、取消机制、流式输出必须有测试覆盖

## 12. 规范的演进

- 用户提出任何新的开发规范时，**先检查本文件是否已覆盖**；没有就先补进本文件，再执行
- 规范冲突时以本文件为准；本文件本身需要修改时，先说明理由再改
