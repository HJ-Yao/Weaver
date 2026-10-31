import type { SessionSnapshotDto } from '@/shared/api/sessions';

/**
 * Session domain model.
 *
 * The UI never renders raw `SessionEvent`s directly. It renders a *transcript*:
 * a flat, ordered list of things the user can see. When event replay lands
 * (TODO(M3)) the transcript becomes the fold output of `SessionEvent[]`; today
 * it is assembled from the snapshot the server already computes.
 */

/** Metadata shown in the session list. */
export interface SessionSummary {
  id: string;
  title: string;
  /** ISO-8601 timestamp; formatted for display by `shared/lib/format`. */
  createdAt: string;
  /** Completed agent steps, when the server reports them. */
  stepCount?: number;
}

/** A chat bubble. `seq` is the event sequence number, when known. */
export interface MessageItem {
  kind: 'message';
  id: string;
  role: 'user' | 'assistant';
  content: string;
  seq?: number;
  /** True while model deltas are still arriving. */
  streaming?: boolean;
}

/** Lifecycle of a single tool invocation. */
export type ToolStatus = 'running' | 'done' | 'failed';

/** A tool call card rendered inline in the transcript. */
export interface ToolCallItem {
  kind: 'tool';
  /** `callId` from the model; pairs the start and completion events. */
  id: string;
  toolName: string;
  argumentsJson: string;
  status: ToolStatus;
  durationMs?: number;
  resultText?: string;
  /** UI-only: whether the result body is expanded. */
  expanded: boolean;
}

/** Whether the user has answered an approval request. */
export type ApprovalStatus = 'pending' | 'approved' | 'rejected';

/** An inline approval request. Approving resumes the blocked run. */
export interface ApprovalItem {
  kind: 'approval';
  id: string;
  toolName: string;
  argumentsJson: string;
  reversibility: 'REVERSIBLE' | 'IRREVERSIBLE';
  timeoutSeconds: number;
  status: ApprovalStatus;
}

/** Anything renderable in the message column. */
export type TranscriptItem = MessageItem | ToolCallItem | ApprovalItem;

/**
 * Rebuild a transcript from a server snapshot.
 *
 * Limitation: `SessionSnapshot` carries no global ordering between messages,
 * tool calls and approvals, so tools/approvals are appended after messages.
 * TODO(M3): replace with an ordered `SessionEvent[]` fold once the server
 * exposes the raw event stream.
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
