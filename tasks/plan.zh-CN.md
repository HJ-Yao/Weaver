# 实施计划：Weaver M1

## 概述

M1 交付 `docs/SPEC-weaver-core.zh-CN.md` 定义的六画面演示：直线式主循环核心（weaver-core）、带 SSE 与 SQLite EventStore 的 Spring 外壳（weaver-server）、React 聊天界面（weaver-ui）。

## 架构决策

- 直线内核 + 两条扩展总线（链钩子 + 事件流），按已批准规格执行；`weaver-core` 保持零框架。
- `EventStore` 接口在 core；SQLite 实现在 server（MyBatis/MySQL 可选，后置）。
- 审批走 SSE/HTTP 阻塞往返——M1 不引入消息队列。
- UI 直连 server；无 BFF、无 API 网关。

## 分支计划（企业 git 规范）

| 分支 | 范围 | PR 目标 |
|---|---|---|
| `feat/core-foundation` | 仅核心：Phase 状态机、最小事件集 + 折叠/重放、策略链 + 直线 RunLoop、审批策略、Tool 接口 + 内置三件套 | main |
| `feat/server-foundation` | Spring 外壳：REST、SSE 中继、SqliteEventStore、模型配置（.env + 运行时）、DeepSeek 模型客户端、审批往返 | main |
| `feat/ui-foundation` | React + Vite 聊天界面六画面（先 mock API，后接真） | main |
| `feat/m1-integration` | 六条成功标准端到端验收走查、演示指引、修复 | main |

分支顺序构建（B1 → B2 → B3 → B4）；任务清单在 `tasks/todo.md`。

## 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 事件/钩子契约中途演化 | 高 | M1 事件集保持最小；契约变更先改规格 + PR 评审 |
| RunLoop 膨胀成上帝类 | 中 | 直线风格 + 评审红灯；支线拆进事件订阅者 |
| SSE 运行中断连 | 中 | 靠回放恢复（事件流是唯一事实源） |
| 审批并发（两会话同时批） | 低 | 审批按 会话+步 定位；M1 每会话单运行 |

## 待定问题

无。