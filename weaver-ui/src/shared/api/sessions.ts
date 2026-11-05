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

/** `cn.yhjyhj.weaver.server.api.dto.SessionEventView` */
export interface SessionEventDto {
  seq: number;
  type:
    | 'USER_MESSAGE'
    | 'ASSISTANT_MESSAGE'
    | 'TOOL_MESSAGE'
    | 'TOOL_STARTED'
    | 'TOOL_COMPLETED'
    | 'APPROVAL_REQUESTED'
    | 'APPROVAL_RESOLVED'
    | 'RUN_FINISHED';
  role: string | null;
  toolName: string | null;
  callId: string | null;
  approved: boolean | null;
  aborted: boolean | null;
  content: string | null;
  argumentsJson: string | null;
  resultContent: string | null;
  resultError: boolean | null;
  reason: string | null;
}

/** 运行中输入的投递目标。 */
export type InboxTargetDto = 'STEP' | 'TURN';

/** 运行中尚未消费的输入条目。 */
export interface InboxEntryDto {
  id: string;
  target: InboxTargetDto;
  content: string;
}

export const sessionApi = {
  /** GET /sessions：供侧栏使用的元数据。 */
  list: () => request<SessionSummaryDto[]>('/sessions'),

  /** POST /sessions：标题可省略，省略时由服务端在首轮后自动命名。 */
  create: (title?: string) =>
    request<SessionSummaryDto>('/sessions', {
      method: 'POST',
      body: JSON.stringify(title ? { title } : {}),
    }),

  /** GET /sessions/{id}：元数据及回放快照。 */
  detail: (id: string) => request<SessionDetailDto>(`/sessions/${encodeURIComponent(id)}`),

  /** GET /sessions/{id}/events：按 seq 升序返回真实事件时间线。 */
  events: (id: string) => request<SessionEventDto[]>(`/sessions/${encodeURIComponent(id)}/events`),

  /** POST /sessions/{id}/run/cancel：请求中止当前运行。 */
  cancelRun: (id: string) =>
    request<void>(`/sessions/${encodeURIComponent(id)}/run/cancel`, { method: 'POST' }),

  /** DELETE /sessions/{id}：删除会话。 */
  remove: (id: string) =>
    request<void>(`/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** PATCH /sessions/{id}：修改标题。 */
  rename: (id: string, title: string) =>
    request<SessionSummaryDto>(`/sessions/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    }),

  /** POST /sessions/{id}/approvals/{callId}：响应 SSE 审批请求。 */
  resolveApproval: (sessionId: string, callId: string, approved: boolean) =>
    request<void>(
      `/sessions/${encodeURIComponent(sessionId)}/approvals/${encodeURIComponent(callId)}`,
      { method: 'POST', body: JSON.stringify({ approved }) },
    ),
};

export const inboxApi = {
  /** POST /sessions/{id}/inbox：运行中投递，成功返回 202。 */
  enqueue: (sessionId: string, content: string, target: InboxTargetDto) =>
    request<InboxEntryDto>(`/sessions/${encodeURIComponent(sessionId)}/inbox`, {
      method: 'POST',
      body: JSON.stringify({ content, target }),
    }),
  /** GET /sessions/{id}/inbox：当前待发送条目。 */
  list: (sessionId: string) =>
    request<InboxEntryDto[]>(`/sessions/${encodeURIComponent(sessionId)}/inbox`),
  /** PATCH /sessions/{id}/inbox/{entryId}：修改文本。 */
  edit: (sessionId: string, entryId: string, content: string) =>
    request<InboxEntryDto>(
      `/sessions/${encodeURIComponent(sessionId)}/inbox/${encodeURIComponent(entryId)}`,
      { method: 'PATCH', body: JSON.stringify({ content }) },
    ),
  /** DELETE /sessions/{id}/inbox/{entryId}：撤回。 */
  remove: (sessionId: string, entryId: string) =>
    request<void>(
      `/sessions/${encodeURIComponent(sessionId)}/inbox/${encodeURIComponent(entryId)}`,
      { method: 'DELETE' },
    ),
};
