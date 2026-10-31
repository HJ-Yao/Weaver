import { request } from './http';

/**
 * Session REST contract.
 *
 * These DTOs mirror the Java records in `weaver-server/api/dto` and
 * `weaver-core/events` one-for-one. Keep them in sync; a field rename on the
 * server is a breaking change for this file.
 */

/** Message role as stored in the event log. */
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
  /** GET /sessions — metadata for the sidebar. */
  list: () => request<SessionSummaryDto[]>('/sessions'),

  /** POST /sessions — creates an empty session. */
  create: (title: string) =>
    request<SessionSummaryDto>('/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  /** GET /sessions/{id} — metadata plus the replayed snapshot. */
  detail: (id: string) => request<SessionDetailDto>(`/sessions/${encodeURIComponent(id)}`),

  /** DELETE /sessions/{id}. */
  remove: (id: string) =>
    request<void>(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** POST /sessions/{id}/approvals/{callId} — answers an SSE approval request. */
  resolveApproval: (sessionId: string, callId: string, approved: boolean) =>
    request<void>(
      `/sessions/${encodeURIComponent(sessionId)}/approvals/${encodeURIComponent(callId)}`,
      { method: 'POST', body: JSON.stringify({ approved }) },
    ),
};
