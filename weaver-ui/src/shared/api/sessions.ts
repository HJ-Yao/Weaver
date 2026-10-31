import { request } from './http';

/**
 * 会话 REST 契约。
 *
 * 这些 DTO 与 `weaver-server/api/dto` 和 `weaver-core/events` 中的 Java record
 * 一一对应。两侧必须同步；服务端字段改名对本文件属于破坏性变更。
 */

/** 事件日志中存储的消息角色。 */
export type MessageRoleDto = 'USER' | 'ASSISTANT' | 'TOOL';

/** `cn.yhjyhj.weaver.server.api.dto.SessionSummary` */
export interface SessionSummaryDto {
  id: string;
  title: string;
  createdAt: string;
}

/** `cn.yhjyhj.weaver.core.events.SessionSnapshot.Message` */
export interface SessionMessageDto {
  role: MessageRoleDto;
  content: string;
}

/** `cn.yhjyhj.weaver.core.events.ToolResult` */
export interface ToolResultDto {
  content: string;
  isError: boolean;
  durationMs: number;
}

/** `cn.yhjyhj.weaver.core.events.SessionSnapshot.ToolCallRecord` */
export interface ToolCallRecordDto {
  callId: string;
  toolName: string;
  argumentsJson: string;
  result: ToolResultDto;
}

/** `cn.yhjyhj.weaver.core.events.SessionSnapshot.ApprovalRecord` */
export interface ApprovalRecordDto {
  callId: string;
  toolName: string;
  approved: boolean;
  reason: string;
}

/** `cn.yhjyhj.weaver.core.events.SessionSnapshot` */
export interface SessionSnapshotDto {
  messages: SessionMessageDto[];
  toolCalls: ToolCallRecordDto[];
  approvals: ApprovalRecordDto[];
  aborted: boolean;
  lastSeq: number;
}

/** `cn.yhjyhj.weaver.server.api.dto.SessionDetail` */
export interface SessionDetailDto {
  summary: SessionSummaryDto;
  snapshot: SessionSnapshotDto;
}

export const sessionApi = {
  /** GET /sessions：供侧栏使用的元数据。 */
  list: () => request<SessionSummaryDto[]>('/sessions'),

  /** POST /sessions：创建一个空会话。 */
  create: (title: string) =>
    request<SessionSummaryDto>('/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  /** GET /sessions/{id}：元数据及回放快照。 */
  detail: (id: string) => request<SessionDetailDto>(`/sessions/${encodeURIComponent(id)}`),

  /** DELETE /sessions/{id}：删除会话。 */
  remove: (id: string) =>
    request<void>(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** POST /sessions/{id}/approvals/{callId}：响应 SSE 审批请求。 */
  resolveApproval: (sessionId: string, callId: string, approved: boolean) =>
    request<void>(
      `/sessions/${encodeURIComponent(sessionId)}/approvals/${encodeURIComponent(callId)}`,
      { method: 'POST', body: JSON.stringify({ approved }) },
    ),
};
