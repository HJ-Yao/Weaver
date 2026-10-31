import type { SessionSnapshotDto } from '@/shared/api/sessions';

/**
 * 会话领域模型。
 *
 * UI 从不直接渲染原始 `SessionEvent`，而是渲染 `transcript`：
 * 一份用户可见内容的扁平有序列表。事件回放落地后（TODO(M3)），
 * `transcript` 将成为 `SessionEvent[]` 的折叠结果；目前由服务端已计算好的快照组装。
 */

/** 会话列表中显示的元数据。 */
export interface SessionSummary {
  id: string;
  title: string;
  /** ISO-8601 时间戳；由 `shared/lib/format` 格式化后显示。 */
  createdAt: string;
  /** 服务端报告时记录的已完成 Agent 步骤数。 */
  stepCount?: number;
}

/** 聊天消息。`seq` 是已知的事件序号。 */
export interface MessageItem {
  kind: 'message';
  id: string;
  role: 'user' | 'assistant';
  content: string;
  seq?: number;
  /** 模型增量仍在到达时为 `true`。 */
  streaming?: boolean;
  /** 本轮回复被用户中止或达到步数上限时为 `true`。 */
  stopped?: boolean;
}

/** 单次工具调用的生命周期。 */
export type ToolStatus = 'running' | 'done' | 'failed';

/** 内联渲染在 transcript 中的工具调用卡片。 */
export interface ToolCallItem {
  kind: 'tool';
  /** 模型提供的 `callId`，用于关联开始事件与完成事件。 */
  id: string;
  toolName: string;
  argumentsJson: string;
  status: ToolStatus;
  durationMs?: number;
  resultText?: string;
  /** 仅用于 UI：结果正文是否展开。 */
  expanded: boolean;
}

/** 用户是否已响应审批请求。 */
export type ApprovalStatus = 'pending' | 'approved' | 'rejected';

/** 内联审批请求。批准后恢复被阻塞的运行。 */
export interface ApprovalItem {
  kind: 'approval';
  id: string;
  toolName: string;
  argumentsJson: string;
  reversibility: 'REVERSIBLE' | 'IRREVERSIBLE';
  timeoutSeconds: number;
  status: ApprovalStatus;
}

/** 消息列中可以渲染的任意内容。 */
export type TranscriptItem = MessageItem | ToolCallItem | ApprovalItem;

/**
 * 根据服务端快照重建 transcript。
 *
 * 限制：`SessionSnapshot` 不包含消息、工具调用和审批之间的全局顺序，
 * 因此工具与审批项会追加在消息之后。
 * TODO(M3): 服务端暴露原始事件流后，改用有序的 `SessionEvent[]` 折叠。
 */
export function transcriptFromSnapshot(snapshot: SessionSnapshotDto): TranscriptItem[] {
  const messages: MessageItem[] = snapshot.messages
    .filter((message) => message.role !== 'TOOL')
    .map((message, index) => ({
      kind: 'message',
      id: `snapshot-message-${index}`,
      role: message.role === 'USER' ? 'user' : 'assistant',
      content: message.content,
    }));

  const tools: ToolCallItem[] = snapshot.toolCalls.map((call) => ({
    kind: 'tool',
    id: call.callId,
    toolName: call.toolName,
    argumentsJson: call.argumentsJson,
    status: call.result.isError ? 'failed' : 'done',
    durationMs: call.result.durationMs,
    resultText: call.result.content,
    expanded: false,
  }));

  const approvals: ApprovalItem[] = snapshot.approvals.map((approval) => ({
    kind: 'approval',
    id: approval.callId,
    toolName: approval.toolName,
    argumentsJson: '',
    reversibility: 'IRREVERSIBLE',
    timeoutSeconds: 0,
    status: approval.approved ? 'approved' : 'rejected',
  }));

  return [...messages, ...tools, ...approvals];
}
