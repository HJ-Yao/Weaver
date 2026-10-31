import { create } from 'zustand';
import { sessionApi } from '@/shared/api/sessions';
import { streamMessage } from '@/shared/api/stream';
import { IS_MOCK } from '@/shared/config/runtime';
import { transcriptFromSnapshot } from './model';
import type {
  ApprovalItem,
  MessageItem,
  SessionSummary,
  ToolCallItem,
  TranscriptItem,
} from './model';

/**
 * 会话状态。
 *
 * 一个 store 统一持有“当前打开的会话”：元数据、可见的 `transcript` 和运行标志。
 * 将它放在这里而不是页面组件中，侧栏、上下文面板和输入区就能读取同一份事实，
 * 无需逐层传递 props。
 */

/** 在真实流式调用成为默认路径前使用的固定回复。 */
const MOCK_REPLY = '收到。我会先读取相关文件，再给出改动方案；涉及写操作时会在这里请求审批。';

/** 会话列表与当前会话共享的生命周期状态。 */
type SessionStatus = 'idle' | 'loading' | 'ready' | 'error';

interface SessionState {
  /** 侧栏中显示的会话。 */
  sessions: SessionSummary[];
  /** 当前打开的会话；尚未选择时为 `null`。 */
  activeSessionId: string | null;
  /** 按渲染顺序排列的可见对话。 */
  transcript: TranscriptItem[];
  /** 运行期间为 `true`，此时输入区保持禁用。 */
  running: boolean;
  /** 会话列表或详情当前的加载状态。 */
  status: SessionStatus;
  /** 最近一次会话请求失败时展示给用户的消息。 */
  error: string | null;

  /** 加载一次会话列表；`force` 为 `true` 时强制重试。 */
  loadSessions: (force?: boolean) => Promise<void>;
  /** 打开会话；真实模式下同时加载可回放的快照。 */
  selectSession: (id: string) => Promise<void>;
  /** 创建新会话并将其设为当前会话。 */
  createSession: (title?: string) => Promise<void>;
  sendMessage: (content: string) => void;
  /** 请求中止当前运行；真实模式等待服务端通过 SSE 确认。 */
  cancelRun: () => void;
  resolveApproval: (callId: string, approved: boolean) => void;
  toggleToolExpanded: (callId: string) => void;
}

let idCounter = 0;
const nextId = (prefix: string) => `${prefix}-${++idCounter}`;

// 快速切换侧栏会话时，详情响应可能乱序返回；只允许最新请求写入 transcript 或错误状态。
let detailRequestId = 0;

/** 未连接服务端时使用的演示元数据。 */
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

/** 演示用 transcript，与已确认的预览一致，便于端到端检查外壳。 */
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

export const useSessionStore = create<SessionState>()((set, get) => {
  const updateItem = (id: string, update: (item: TranscriptItem) => TranscriptItem) => {
    set((state) => ({
      transcript: state.transcript.map((item) => (item.id === id ? update(item) : item)),
    }));
  };

  return {
    sessions: IS_MOCK ? demoSessions : [],
    activeSessionId: IS_MOCK ? (demoSessions[0]?.id ?? null) : null,
    transcript: IS_MOCK ? demoTranscript : [],
    running: false,
    status: IS_MOCK ? 'ready' : 'idle',
    error: null,

    loadSessions: async (force = false) => {
      if (!force && get().status !== 'idle') return;

      if (IS_MOCK) {
        set({ status: 'ready', error: null });
        return;
      }

      set({ status: 'loading', error: null });
      try {
        const sessions = await sessionApi.list();
        set({ sessions });

        const firstSession = sessions[0];
        if (!get().activeSessionId && firstSession) {
          await get().selectSession(firstSession.id);
          return;
        }

        set({ status: 'ready', error: null });
      } catch (error) {
        set({
          status: 'error',
          error: `会话列表加载失败：${readableError(error)}`,
        });
      }
    },

    selectSession: async (id) => {
      if (IS_MOCK) {
        set({ activeSessionId: id });
        return;
      }

      const requestId = ++detailRequestId;
      set({ activeSessionId: id, error: null });

      try {
        const detail = await sessionApi.detail(id);
        if (requestId !== detailRequestId) return;

        set({
          transcript: transcriptFromSnapshot(detail.snapshot),
          status: 'ready',
          error: null,
        });
      } catch (error) {
        if (requestId !== detailRequestId) return;

        // 保留当前可见的 transcript；服务端恢复后，下一次成功选择会将其替换。
        set({
          status: 'error',
          error: `会话详情加载失败：${readableError(error)}`,
        });
      }
    },

    createSession: async (title = '新会话') => {
      if (IS_MOCK) {
        const session: SessionSummary = {
          id: `${Date.now()}`,
          title,
          createdAt: new Date().toISOString(),
        };
        set((state) => ({
          sessions: [...state.sessions, session],
          activeSessionId: session.id,
          transcript: [],
          status: 'ready',
          error: null,
        }));
        return;
      }

      try {
        const session = await sessionApi.create(title);
        // 新会话本身就是空的，因此要使仍在进行的详情请求失效，避免旧会话覆盖它。
        detailRequestId += 1;
        set((state) => ({
          sessions: [...state.sessions, session],
          activeSessionId: session.id,
          transcript: [],
          status: 'ready',
          error: null,
        }));
      } catch (error) {
        set({
          status: 'error',
          error: `新建会话失败：${readableError(error)}`,
        });
      }
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

      if (IS_MOCK) {
        // 模拟响应同步完成，测试不依赖定时器。
        // TODO(M2): 服务端始终可用后移除模拟分支。
        updateItem(reply.id, (item) =>
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
          updateItem(reply.id, (item) =>
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
        onToolResult: (result) => {
          set((state) => ({
            transcript: state.transcript.map((item) =>
              item.kind === 'tool' && item.id === result.callId
                ? {
                    ...item,
                    status: result.isError ? 'failed' : 'done',
                    durationMs: result.durationMs,
                    resultText: result.content,
                  }
                : item,
            ),
          }));
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
        onAborted: () => {
          updateItem(reply.id, (item) =>
            item.kind === 'message' ? { ...item, streaming: false, stopped: true } : item,
          );
          set({ running: false });
        },
        onDone: () => {
          updateItem(reply.id, (item) =>
            item.kind === 'message' ? { ...item, streaming: false } : item,
          );
          set({ running: false });
        },
        onError: (message) => {
          updateItem(reply.id, (item) =>
            item.kind === 'message'
              ? { ...item, content: `运行失败：${message}`, streaming: false }
              : item,
          );
          set({ running: false });
        },
      }).catch(() => {
        updateItem(reply.id, (item) =>
          item.kind === 'message' ? { ...item, streaming: false } : item,
        );
        set({ running: false });
      });
    },

    cancelRun: () => {
      if (IS_MOCK) {
        set({ running: false });
        return;
      }

      const sessionId = get().activeSessionId;
      if (sessionId) {
        void sessionApi.cancelRun(sessionId).catch(() => undefined);
      }
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
      if (!IS_MOCK && sessionId) {
        // 被阻塞的运行正在等待这次 HTTP 调用；若失败，服务端会将其表现为超时。
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
  };
});

/** 将未知的请求失败转换为适合界面展示的文本。 */
function readableError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message || '未知错误';
}
