# weaver-ui

Weaver 的 Web 控制台：会话对话、工具调用、审批、Diff、回放、插件与设置。

当前是 **M1 阶段的前端骨架**——布局、主题、路由、状态边界和 API 契约已经就位，
真实数据接入与回放等高阶能力以 `TODO(M2/M3/M4)` 标记在代码里。

## 快速开始

```bash
cd weaver-ui
npm install
npm run dev          # http://localhost:5173
```

默认使用内置的演示数据（`VITE_USE_MOCK=false` 之前不会请求后端）。

## 常用脚本

| 命令 | 作用 |
|---|---|
| `npm run dev` | 启动开发服务器，`/api` 代理到 `localhost:8080` |
| `npm run build` | `tsc -b` + 生产构建，产物在 `dist/` |
| `npm run test` | Vitest 单元测试 |
| `npm run lint` | ESLint（flat config） |
| `npm run typecheck` | 只做类型检查 |

提交前请依次通过：`typecheck → lint → test → build`。

## 连接后端

`vite.config.ts` 把 `/api` 代理到 Spring Boot（默认 `http://localhost:8080`）。
需要在浏览器里跑真实流式会话时：

```bash
VITE_USE_MOCK=false npm run dev
```

其他环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE` | `/api` | REST 基础路径 |
| `VITE_USE_MOCK` | `true` | 设为 `false` 时走真实 SSE |

## 目录

```
src/
  app/       外壳、路由、全局 UI 状态（主题 / 面板 / toast）
  pages/     路由级页面：chat、plugins、settings
  widgets/   复合块：rail、sidebar、context-panel
  features/  有动作的功能：composer、message-stream、tool-call、approval、diff-viewer
  entities/  领域模型与状态：session、diff、model-config
  shared/    基础设施：api、ui、lib、styles
```

分层与依赖方向见 [CONVENTIONS.md](CONVENTIONS.md)。

## 设计来源

视觉与交互沿用 `local/ui-preview/` 中已确认的静态预览（不提交）。颜色、间距、
圆角全部收敛在 `src/shared/styles/tokens.css`。
