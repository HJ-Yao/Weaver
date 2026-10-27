# 规格：Weaver Core（M1）

English | [简体中文](SPEC-weaver-core.zh-CN.md)

## 目标

交付 M1 地基：可运行的 Agent 编码循环 + 一个涵盖六画面的 Web 演示——流式推理、工具调用卡片、审批弹窗、Diff 预览、事件回放、多会话管理。

用户故事：我打开 Weaver，新建会话，让 Agent 在工作区内修改代码。我看着模型流式输出思考，每步工具调用显示为卡片（读文件 / 跑命令 / git 操作，带耗时），危险命令执行前弹确认框，完成后查 git diff、决定留不留、一键提交。之后任意会话可重新打开并逐步回放；多个会话并存互不串数据。

超出范围（后置里程碑）：MCP 接入与 SKILL.md 加载、上下文压缩、子代理、三形态插件契约、MySQL 之外的更高量级后端、CLI 入口。

## 技术栈

- Java 21——`weaver-core` **零框架**（仅 JDK + Jackson）。
- Spring Boot 3.5.3（仅 server 外壳），Spring Web（REST + `SseEmitter`）。
- 持久化：core 定义 `EventStore` 接口；默认 SQLite 实现（JDBC）；MyBatis + MySQL 作为可选实现。
- 前端：React + Vite（HTTP + SSE；不用 GraphQL）。
- 测试：JUnit 5；server 集成测试用 Spring test slice。

## 命令

```bash
mvn -q compile          # 编译全部模块
mvn -q test             # 运行测试
mvn -q -pl weaver-server spring-boot:run   # 启动服务（SQLite 零配置）
cd weaver-ui && npm install && npm run dev # 启动前端
```

## 项目结构

```
weaver-core/src/main/java/io/github/hjyao/weaver/core/
  loop/       # RunLoop、Turn、StrategyHandler、链节点（Resolve/Intent/Dispatch/Collect）
  phase/      # sealed Phase（Idle/Running/Maintenance）、abort、wake
  events/     # sealed SessionEvent、seq、SessionEventStore 接口
  policy/     # ApprovalPolicy、允许/拒绝规则表
  tool/       # Tool 接口与内置实现：FileTool、ShellTool、GitTool
  model/      # ModelClient SPI、StreamChunk 类型
weaver-server/src/main/java/.../server/
  api/        # 会话、运行、审批、回放端点
  sse/        # 流式中继
  store/      # SqliteEventStore、MyBatisEventStore（可选）
  config/     # 模型设置（.env 默认值、运行时覆盖）
weaver-ui/src/  # 聊天视图、工具卡片、审批弹窗、Diff 视图、回放播放器、会话列表
docs/        # 规格、ADR
```

## 架构

借鉴 Pi（packages/agent 作为循环核心）与 Codex（codex-rs 分 crate 拆分）：

1. **一个能力一个包**——像 Pi 的 `packages/{agent,ai,durable,mcp,tui}`：循环放 `loop/`，模型边界放 `model/`，持久化*接口*放 `events/`，执行放 `tool/`。入口模块负责聚合（weaver-server / weaver-cli），核心从不反身引用外部。
2. **直线核心 + 事件当支线总线**——主循环集中一处自上而下读（`RunLoop`），完全像 Pi 单个 `agent-loop.ts`；循环*产出*的一切都是 `SessionEvent`。对产出做反应的东西（落库、推 SSE、回放）订阅事件，而不是被循环调用——没有深调用链。
3. **两条扩展总线**——钩子/链节点（`StrategyHandler` 槽位：Resolve/Intent/Dispatch/Collect + 借自 Pi 的 `finishTurn` 式决策）改变*行为*；事件改变/观察*状态*。新能力 = 新节点注册或新事件处理器，永不编辑循环。

## 代码风格

复盘得出的"臃肿规则"是合同，不是建议：

1. **小方法/小类（尽力而为，非硬性上限）**：方法尽量 ≤ 40 行、类尽量 ≤ 250 行；如果某段逻辑整体成一条流读起来更顺，允许更长——但抽方法必须降低复杂度，不能只是把复杂度藏起来。
2. **委托深度（红灯检查项，非硬性上限）**：优先直线式流程；当委托链长到约 3 跳以上（A → B → C → D），停下来问一句"中间人是不是该换成事件、组合对象或直接内联"——就像 Pi 把主循环留在一个直线文件里，把支线用事件和钩子回调挪出去。
3. **直线式循环逻辑**：主循环读起来像一张表——准备回合 → 调模型 → 执行工具 → 决定是否结束。用早退分支，不要 if/else 金字塔。
4. **依赖单向**：`weaver-core` 不 import 任何其他模块；包之间只依赖接口，不依赖实现。
5. **命名**：用领域词（`RunLoop`、`Turn`、`Step`、`SessionEvent`）。禁止 `Manager`/`Util` 这类泛词；`StrategyHandler` 保留（它是链契约名）。
6. **注释规范（借鉴 Pi / Codex / Claude Code）**：
   - 注释主要写在**公开边界**（接口、sealed 类型、公开方法）：一两句"是什么"+ 契约语义——不变式、"不得抛异常"、"缺少 X 视为 no-op"，风格同 Pi 的钩子文档与 Codex 的 `AgentGraphStore` trait 文档。
   - 实现内部只注释**为什么与不变量**。绝不注释"在做什么"——名字自己会说话。不要装饰性注释、作者/日期噪音、没挂 issue 链接的 `TODO`。
   - **注释语言用中文**（维护者主力语言）；将来可能变成公开文档的注释可中英双语。简洁、精确；`@param`/`@return` 仅在签名和名字表达不清楚时才写。

## 测试策略

- JUnit 5；`weaver-core` 分支覆盖率 ≥ 80%（JaCoCo 门槛）。
- 单元：每个 `SessionEvent` 变体折叠正确；从 seq 0 回放能重建状态；审批规则表驱动测试（放行/拦截用例）；步数上限与中止路径。
- 集成（server）：SSE 送达流式分片；审批往返在得到答复前暂停运行；两个并发会话互不串数据。
- UI：六画面做 mock-API 组件测试；UI 测试不承担后端逻辑断言。

## 边界

- **Always（必须做）**：核心逻辑 TDD；提交前 `mvn -q test` 全绿；文档中英成对；事件 seq 单调；回放必须复现状态。
- **Ask first（先问再做）**：库表结构变更、新增依赖、审批规则变更、并发模型变更、任何动 `RunLoop` 契约的修改。
- **Never（绝不做）**：提交密钥；`weaver-core` import Spring；破坏事件向后兼容；为了"整齐"叠代理层；没有第二个消费者的投机抽象。

## 成功标准（M1 验收）

1. 流式：UI 可见首分片；工具卡片显示工具名、参数摘要、耗时。
2. 审批：`rm`、`git commit/push`、装依赖 → 弹窗；工作区内只读、`git status/diff` → 自动放行。
3. Diff：一轮修改后 UI 显示 diff；批准 → 提交生效。
4. 回放：任意已结束会话可从 seq 0 重放；折叠状态与实时状态一致。
5. 会话：多会话增删切换永不串事件。
6. 简洁：代码审查通过上述行数/深度规则；`weaver-core` 零 Spring 引用（用测试验证）。

## 待定问题

无——访谈已全部收敛（场景 A、六画面、单用户、SQLite 默认 + MyBatis/MySQL 可选、白名单审批、.env + 设置页模型配置）。