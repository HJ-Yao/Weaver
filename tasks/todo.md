# M1 Task List

Task indices owned by branch (see `tasks/plan.md`). Check off only after PR merge.

## Branch 1: feat/core-foundation

- [ ] T1 Phase state machine: sealed `Phase` (Idle/Running/Maintenance), `abort`/`wakeRequested`, unit tests
- [ ] T2 Minimal `SessionEvent` set (seq, fold, replay from 0) + `EventStore` interface, fold/replay tests
- [ ] T3 Chain contract `StrategyHandler` + four nodes skeleton + straight-line `RunLoop` with fake `ModelClient`; step cap 50, abort, `StreamChunk` flow
- [ ] T4 `ApprovalPolicy` table-driven (allow/deny) + `Tool` interface with FileTool/ShellTool/GitTool
- [ ] T5 Zero-Spring guard test for weaver-core + branch coverage ≥ 80%
- [ ] Checkpoint: `mvn -q test` green; review depth/line guidelines; open PR → main

## Branch 2: feat/server-foundation

- [ ] T6 Spring Boot app: session CRUD REST + SSE relay wiring
- [ ] T7 `SqliteEventStore` implementing core interface + round-trip tests
- [ ] T8 Model config (.env defaults + runtime override) + DeepSeek `ModelClient`
- [ ] T9 Approval round-trip: run pauses until user answers; SSE delivers streaming chunks
- [ ] Checkpoint: integration tests green; PR → main

## Branch 3: feat/ui-foundation

- [ ] T10 React + Vite scaffold, session list + chat view against mock API
- [ ] T11 Tool-call cards + approval popup + diff preview views
- [ ] T12 Replay player view + wire live SSE
- [ ] Checkpoint: component tests pass; manual walkthrough; PR → main

## Branch 4: feat/m1-integration

- [ ] T13 End-to-end acceptance against the 6 success criteria; demo guide (`docs/demo.md` + zh)
- [ ] T14 Fix issues found in the walkthrough; final review
- [ ] Checkpoint: all criteria verified; tag `v0.1.0`