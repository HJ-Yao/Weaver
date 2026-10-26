# Roadmap / 路线图

The project is built incrementally, from a working core outward.
项目以“先跑通核心，再向外扩展”的方式逐步构建。

| Milestone | Focus | Deliverable / 产出 |
|---|---|---|
| M0 | Foundation / 地基 | Repo skeleton, spec, conventions / 仓库骨架、规格、规范 |
| M1 | Core loop / 核心循环 | Strategy chain + execution chain (Resolve → Intent → Dispatch → Collect) / 策略链 + 执行链 |
| M2 | Lifecycle / 生命周期 | Inbox-driven state machine, streaming, abort, step cap / 状态机、流式输出、中止、步数上限 |
| M3 | Traceability & plugins / 溯源与插件 | Event-sourced sessions, replay; SPI / Node bridge / MCP plugins / 事件溯源会话、回放、三形态插件 |
| M4 | Product / 产品化 | React chat UI, CLI, docs, CI, release / 聊天界面、CLI、文档、CI、发版 |