# Implementation Plan: Weaver M1

## Overview

M1 delivers the six-view demo defined in `docs/SPEC-weaver-core.md`: a straight-line run loop core (weaver-core), a Spring shell with SSE and SQLite EventStore (weaver-server), and a React chat UI (weaver-ui).

## Architecture Decisions

- Straight-line kernel + two extension buses (chain hooks + event stream), per the approved spec; `weaver-core` stays zero-framework.
- `EventStore` interface lives in core; SQLite implementation lives in server (MyBatis/MySQL optional, later).
- Approval is a blocking round-trip over SSE/HTTP for M1 — no message queue.
- UI talks directly to the server; no BFF or API gateway.

## Branch Plan (enterprise git)

| Branch | Scope | PR → |
|---|---|---|
| `feat/core-foundation` | Core only: Phase state machine, minimal event set + fold/replay, strategy chain + straight-line RunLoop, approval policy, Tool interface + three built-ins | main |
| `feat/server-foundation` | Spring Boot shell: REST, SSE relay, SqliteEventStore, model config (.env + runtime), DeepSeek model client, approval round-trip | main |
| `feat/ui-foundation` | React + Vite chat UI with the six views (mock API first, then live) | main |
| `feat/m1-integration` | End-to-end acceptance walkthrough of the 6 success criteria, demo guide, fixes | main |

Branches are built sequentially (B1 → B2 → B3 → B4); task lists live in `tasks/todo.md`.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Event/hook contracts evolve mid-build | High | Keep M1 event set minimal; contract changes go through a spec edit + PR review first |
| RunLoop grows into a god-class | Med | Straight-line style + review flag; split tracks into event subscribers |
| SSE disconnect mid-run | Med | Resume via replay (event stream is the source of truth) |
| Approval concurrency (two sessions approve simultaneously) | Low | Approvals keyed by session+step; engine stays single-run per session in M1 |

## Open Questions

None.