import { create } from 'zustand';
import { sessionApi } from '@/shared/api/sessions';
import { streamMessage } from '@/shared/api/stream';
import type { ApprovalItem, MessageItem, SessionSummary, ToolCallItem, TranscriptItem } from './model';

/**
 * Session state.
 *
 * One store owns "the open conversation": metadata, the visible transcript and
 * the running flag. Keeping it here (instead of in the page component) lets the
 * sidebar, context panel and composer read the same facts without prop drilling.
 */

/** When `false`, the store streams from the Spring Boot server instead of mocking. */
const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';

/** Canned reply used until the live stream is the default. */
const MOCK_REPLY = '收到。我会先读取相关文件，再给出改动方案；涉及写操作时会在这里请求审批。';

interface SessionState {
  /** Sessions shown in the sidebar. */
  sessions: SessionSummary[];
  /** Currently open session, or `null` before anything is selected. */
  activeSessionId: string | null;
  /** Visible conversation, in render order. */
  transcript: TranscriptItem[];
  /** True while a run is in flight; the composer stays disabled. */
  running: boolean;

  selectSession: (id: string) => void;
  sendMessage: (content: string) => void;
  resolveApproval: (callId: string, approved: boolean) => void;
  toggleToolExpanded: (callId: string) => void;
}

let idCounter = 0;
const nextId = (prefix: string) => `${prefix}-${++idCounter}`;

/** Demo metadata; TODO(M2): replace with `sessionApi.list()` on mount. */
const demoSessions: SessionSummary[] = [
  {
    id: 'demo-approval',
    title: '审批栈重构',
    createdAt: '2026-10-31T12:00:00Z',
    stepCount: 12,
  },
  {
    id: 'demo-sqlite',
    title: 'SQLite 事件存储',
    createdAt: '2026-10-31T11:00:00Z',
    stepCount: 34,
  },
  { id: 'demo-schema', title: '工具 schema 设计', createdAt: '2026-10-31T09:00:00Z', stepCount: 8 },
  { id: 'demo-react', title: 'ReAct 五节点', createdAt: '2026-10-30T14:14:00Z', stepCount: 27 },
  { id: 'demo-symlink', title: '符号链接逃逸', createdAt: '2026-10-30T10:02:00Z', stepCount: 15 },
];

/** Demo transcript; mirrors the approved preview so the shell can be reviewed end to end. */
const demoTranscript: TranscriptItem[] = [
  {
    kind: 'message',
    id: 'demo-message-1',
    role: 'user',
    content: '把审批层重构成固定顺序的裁决栈，规则表不要再靠正则啃 JSON。',
  },
  {
    kind: 'message',
    id: 'demo-message-2',
    role: 'assistant',
    content:
      '我先读当前实现，确认规则匹配和回滚性修正的位置，然后拆成「硬禁区 → deny → ask → allow → fallback → 模式修正」。',
    seq: 4,
  },
  {
    kind: 'tool',
    id: 'demo-call-1',
    toolName: 'file.read',
    argumentsJson: '{"path":"core/approval/RuleBasedApprovalPolicy.java"}',
    status: 'done',
    durationMs: 142,
    resultText: 'public final class RuleBasedApprovalPolicy implements ApprovalPolicy {\n  // …\n}',
    expanded: false,
  },
  {
    kind: 'tool',
    id: 'demo-call-2',
    toolName: 'shell',
    argumentsJson: '{"command":"mvn -q -pl weaver-core test"}',
    status: 'done',
    durationMs: 3100,
    resultText: 'Tests run: 228, Failures: 0, Errors: 0, Skipped: 4',
    expanded: false,
  },
  {
    kind: 'message',
    id: 'demo-message-3',
    role: 'assistant',
    content: '裁决栈已拆成五步。下一步会提交改动，需要你确认。',
    seq: 9,
  },
  {
    kind: 'approval',
    id: 'demo-call-3',
    toolName: 'git.commit',
    argumentsJson: '{"op":"commit","message":"refactor(core): linearize approval decision steps"}',
    reversibility: 'IRREVERSIBLE',
    timeoutSeconds: 120,
    status: 'pending',
  },
];

export const useSessionStore = create<SessionState>()((set, get) => ({
  sessions: demoSessions,
  activeSessionId: demoSessions[0]?.id ?? null,
  transcript: demoTranscript,
  running: false,

  selectSession: (id) => {
    set({ activeSessionId: id });
    // TODO(M2): GET /sessions/{id} and rebuild the transcript with `transcriptFromSnapshot`.
  },

  sendMessage: (content) => {
    const text = content.trim();
    if (!text || get().running) return;

    const user: MessageItem = {
      kind: 'message',
      id: nextId('message'),
      role: 'user',
      content: text,
    };
    const reply: MessageItem = {
      kind: 'message',
      id: nextId('message'),
      role: 'assistant',
      content: '',
      streaming: true,
    };

    set((state) => ({ transcript: [...state.transcript, user, reply], running: true }));

    if (USE_MOCK) {
      // The mock resolves synchronously so tests never depend on timers.
      // TODO(M2): drop the mock branch once the server is always running.
      patchItem(set, reply.id, (item) =>
        item.kind === 'message' ? { ...item, content: MOCK_REPLY, streaming: false } : item,
      );
      set({ running: false });
      return;
    }

    const sessionId = get().activeSessionId;
    if (!sessionId) {
      set({ running: false });
      return;
    }

    void streamMessage(sessionId, text, {
      onDelta: (delta) =>
        patchItem(set, reply.id, (item) =>
          item.kind === 'message' ? { ...item, content: item.content + delta } : item,
        ),
      onTool: (call) => {
        const tool: ToolCallItem = {
          kind: 'tool',
          id: call.callId,
          toolName: call.toolName,
          argumentsJson: call.argumentsJson,
          status: 'running',
          expanded: false,
        };
        set((state) => ({ transcript: [...state.transcript, tool] }));
      },
      onApproval: (approval) => {
        const approvalItem: ApprovalItem = {
          kind: 'approval',
          id: approval.callId,
          toolName: approval.toolName,
          argumentsJson: approval.argumentsJson,
          reversibility: approval.reversibility,
          timeoutSeconds: approval.timeoutSeconds,
          status: 'pending',
        };
        set((state) => ({ transcript: [...state.transcript, approvalItem] }));
      },
      onDone: () => {
        patchItem(set, reply.id, (item) =>
          item.kind === 'message' ? { ...item, streaming: false } : item,
        );
        set({ running: false });
      },
      onError: (message) => {
        patchItem(set, reply.id, (item) =>
          item.kind === 'message'
            ? { ...item, content: `运行失败：${message}`, streaming: false }
            : item,
        );
        set({ running: false });
      },
    }).catch(() => {
      patchItem(set, reply.id, (item) =>
        item.kind === 'message' ? { ...item, streaming: false } : item,
      );
      set({ running: false });
    });
  },

  resolveApproval: (callId, approved) => {
    set((state) => ({
      transcript: state.transcript.map((item) =>
        item.kind === 'approval' && item.id === callId
          ? { ...item, status: approved ? 'approved' : 'rejected' }
          : item,
      ),
    }));

    const sessionId = get().activeSessionId;
    if (!USE_MOCK && sessionId) {
      // The blocked run is waiting on this HTTP call; failures surface as a timeout on the server.
      void sessionApi.resolveApproval(sessionId, callId, approved).catch(() => undefined);
    }
  },

  toggleToolExpanded: (callId) => {
    set((state) => ({
      transcript: state.transcript.map((item) =>
        item.kind === 'tool' && item.id === callId ? { ...item, expanded: !item.expanded } : item,
      ),
    }));
  },
}));

/** Replace one transcript item in place, keeping array identity stable otherwise. */
function patchItem(
  set: (fn: (state: SessionState) => Partial<SessionState>) => void,
  id: string,
  update: (item: TranscriptItem) => TranscriptItem,
): void {
  set((state) => ({
    transcript: state.transcript.map((item) => (item.id === id ? update(item) : item)),
  }));
}
