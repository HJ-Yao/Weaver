# weaver-ui 前端开发规范

适用范围：`weaver-ui/` 下的全部代码。与仓库根 [AGENTS.md](../AGENTS.md) 冲突时以根文件为准，
本文件只补充前端专属约定。

## 1. 技术栈

| 层 | 选择 | 理由 |
|---|---|---|
| 构建 | Vite 6 + TypeScript 5.8（strict） | 快、零配置、产物小 |
| 视图 | React 19 + react-router 7 | 页面只有三个，路由即状态 |
| 状态 | Zustand 5 | 足够小，selector 精确订阅 |
| 样式 | CSS 变量 + CSS Modules | 设计是自研的，组件库会失真 |
| 测试 | Vitest + Testing Library + jsdom | 与 Vite 同源 |

**明确不引入**：Tailwind（与既有 token 体系冲突）、组件库（视觉失配）、Redux（收益不匹配）、
axios（`fetch` 已够用）。新增依赖需要在提交说明里写清理由。

## 2. 目录分层与依赖方向

```
src/
  app/      外壳、路由表、全局 UI 状态
  pages/    路由级页面：只做布局与组合
  widgets/  可独立摆放的复合块：rail、sidebar、context-panel
  features/ 有明确动作的功能：composer、message-stream、approval、diff-viewer
  entities/ 领域模型与领域状态：session、diff、model-config
  shared/   无业务含义的基础设施：api、ui、lib、styles
```

依赖只能单向向左：

```
pages → widgets → features → entities → shared
```

- 页面不直接发请求；只调用 entity store 或 feature 的公开接口。
- `shared/` 不得 import 任何上层模块。
- 禁止跨层循环依赖；同层之间尽量通过 props 或 store 通信。
- 一个目录直接文件 ≤ 8 个，超出按子职责再分（沿用根 `AGENTS.md` §5）。

## 3. 命名

| 对象 | 约定 | 示例 |
|---|---|---|
| 组件文件 | `PascalCase.tsx` | `MessageStream.tsx` |
| 组件样式 | 同名 `*.module.css` | `MessageStream.module.css` |
| 非组件模块 | `camelCase.ts` | `store.ts`、`format.ts` |
| CSS class | `camelCase` | `styles.panelHead` |
| 事件回调 | `onXxx` | `onSubmit`、`onDelta` |
| 布尔 | `is` / `has` / `can` 前缀 | `isComposing`、`hasFocus` |
| 常量 | `UPPER_SNAKE_CASE` | `TICK_MS` |

## 4. 组件

- 一个组件一个职责；单文件超过 **200 行**视为拆分信号。
- 展示与数据分离：容器读 store，展示组件只接收 props（见 `ToolCard` / `MessageStream`）。
- 组合优于配置：用 `children` 和小组件组合，不要堆 `variant` / `size` 开关。
- 交互元素用原生 `<button>` / `<a>`；`div` 加点击必须补 `role` + `tabIndex` + 键盘处理。
- 禁止内联 `style`，唯一例外是写入 CSS 变量的动态值（如色板 `--swatch`）。

## 5. 状态管理

| 场景 | 选择 |
|---|---|
| 组件内部 UI 状态 | `useState` |
| 两三个兄弟组件共享 | 状态提升 |
| 外壳级 UI（主题、面板、tab、toast、审批模式） | `app/ui-store.ts` |
| 会话事实（transcript、运行中） | `entities/session/store.ts` |
| 可分享的筛选（插件 tab） | URL search params |

- selector 返回数组或对象时**必须**用 `useShallow`，否则每次 store 更新都会重渲染。
- 派生数据用 selector 计算，不往 store 里塞可推导的副本。
- 服务端缓存（TanStack Query 等）等到 M2 真有分页/失效需求再评估，现在不引入。

## 6. 样式

- 颜色、间距、圆角、字号、层级**只能**取自 `shared/styles/tokens.css`；禁止裸十六进制与随手像素值
  （`0`、`1px`、`100%` 除外）。间距用 `--space-1..8`（4px 步进）。
- 组件样式一律 CSS Modules；全局样式只有 `tokens.css` 与 `base.css`。
- 布局：grid 模板只声明一次，列宽通过 CSS 变量切换（见 `AppShell.module.css`）。
- 主题：`:root[data-theme]` + `:root[data-accent]`；新增主题必须同时提供暗色与亮色值。
- 对比度目标 WCAG AA：正文 4.5:1、大字 3:1。已知不达标项见 §12。
- 动效 ≤ 200ms；`prefers-reduced-motion` 已在 `base.css` 全局关闭动画。

## 7. 数据边界

- 所有 HTTP 请求走 `shared/api/`，组件里禁止出现 `fetch`。
- DTO 与 Java record 一一对应；字段改名视为破坏性变更，两侧同步改。
- 流式：`EventSource` 不能发 POST，因此用 `fetch` + 手写 SSE 解析；未知事件必须静默忽略。
- 错误统一为 `ApiError`（带 `status`），UI 必须给出错误态而不是空白。
- **密钥永不进入前端**：API Key 由服务端环境变量持有，浏览器只提交配置名。

## 8. 可访问性

- 所有交互元素键盘可达，Tab 顺序与视觉顺序一致。
- 纯图标按钮必须有 `aria-label`；装饰性图标 `aria-hidden`。
- 覆盖层（Diff、弹窗）支持 `Esc` 关闭，并把焦点还给触发元素。
- 状态不能只靠颜色表达，必须同时有文字（如工具卡片的"完成 / 运行中 / 失败"）。
- 模态使用原生 `<dialog>`，不要手写遮罩层。

## 9. 测试

- 新逻辑先写测试（先红后绿）。
- 测行为不测实现：用 `getByRole` / `getByLabelText` 查询，不查 class、不查组件内部 state。
- 每个 store action 至少一个用例；`shared/lib` 纯函数全覆盖。
- 提交前必须 `npm run typecheck && npm run lint && npm run test && npm run build` 全绿。

## 10. 注释与 TODO

- **前端所有代码注释一律使用中文**；代码标识符、类型名、API 路径、CSS 类名、字符串字面量和里程碑编号除外。
- 导出的模块、组件、类型必须有用途说明；注释解释**为什么**，不复述代码。
- 可能进入公开文档的注释使用中英双语。
- 未完成项必须写成 `// TODO(M2): 具体事项`，**带里程碑编号**，禁止空 TODO 或"以后优化"。
- 禁止抽取没有独立语义、只负责转发参数或回调的辅助函数，也不要为了缩短代码增加多层回调；
  代码应当能自上而下读完，不需要在文件之间反复跳转。

## 11. Git

- Conventional Commits：`feat` / `fix` / `refactor` / `docs` / `test` / `chore`，scope 用 `ui`。
- 一个可构建、测试通过的逻辑变更一个 commit；前后端改动分开提交。
- 提交前跑完第 9 节的四条命令。

## 12. 当前 TODO 清单

**M2 — 接真实数据**

- [ ] 会话列表 / 详情接 `GET /api/sessions`（替换 `store.ts` 中的 `demoSessions`）
- [ ] 发送消息接真实 SSE（`VITE_USE_MOCK=false`），失败态与重连策略
- [ ] 工作区与模型配置持久化到 `~/.weaver/config.json`
- [ ] 插件 / 技能目录接后端；公开 / 个人筛选下沉到服务端
- [ ] 状态栏的 tokens、seq、连接状态改为真实值

**M3 — 回放与溯源**

- [ ] `SessionEvent[]` 折叠为 transcript，替换 `transcriptFromSnapshot` 的近似实现
- [ ] 回放时间线由真实事件驱动（替换 `ReplayPanel` 中的 `DEMO_EVENTS`）
- [ ] Diff 由工作区接口提供（替换 `entities/diff/model.ts` 的演示数据）

**M4 — 产品化**

- [ ] 快捷键绑定到真实命令；补命令面板
- [ ] 字号 / 密度选项（当前为禁用占位）
- [ ] Tauri 打包；路由切换为 hash / memory（`router.tsx` 已有 TODO）
- [ ] 移动端：上下文栏改为抽屉（`AppShell.module.css` 已有 TODO）
- [ ] 对比度审计：`--faint` 约 3.36:1；深色模式 teal/green/amber 主色上的发送按钮约 3.0–3.8:1
- [ ] 品牌图优化：当前 `public/brand/weaver-logo.png` 为 128px，正式发版再出多尺寸
