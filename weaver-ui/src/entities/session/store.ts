import { create } from 'zustand';
import { sessionApi } from '@/shared/api/sessions';
import { streamMessage } from '@/shared/api/stream';
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

/** 会话列表与当前会话共享的生命周期状态。 */
type SessionStatus = 'idle' | 'loading' | 'ready' | 'error';

interface SessionState {
  /** 侧栏中显示的会话。 */
  sessions: SessionSummary[];
  /** 当前打开的会话；尚未选择时为 `null`。 */
  activeSessionId: string | null;
  /** 按渲染顺序排列的可见对话。 */
  transcript: TranscriptItem[];
  /** 当前会话快照中最后一条事件序号；尚未加载时为 `0`。 */
  lastSeq: number;
  /** 运行期间为 `true`，此时输入区保持禁用。 */
  running: boolean;
  /** 会话列表或详情当前的加载状态。 */
  status: SessionStatus;
  /** 最近一次会话请求失败时展示给用户的消息。 */
  error: string | null;

  /** 加载一次会话列表；`force` 为 `true` 时强制重试。 */
  loadSessions: (force?: boolean) => Promise<void>;
  /** 打开会话并加载服务端快照。 */
  selectSession: (id: string) => Promise<void>;
  /** 创建新会话并将其设为当前会话。 */
  createSession: (title?: string) => Promise<void>;
  sendMessage: (content: string) => void;
  /** 请求中止当前运行；等待服务端通过 SSE 确认。 */
  cancelRun: () => void;
  resolveApproval: (callId: string, approved: boolean) => void;
  toggleToolExpanded: (callId: string) => void;
}

let idCounter = 0;
const nextId = (prefix: string) => `${prefix}-${++idCounter}`;

// 快速切换侧栏会话时，详情响应可能乱序返回；只允许最新请求写入 transcript 或错误状态。
let detailRequestId = 0;

export const useSessionStore = create<SessionState>()((set, get) => {
  const updateItem = (id: string, update: (item: TranscriptItem) => TranscriptItem) => {
    set((state) => ({
      transcript: state.transcript.map((item) => (item.id === id ? update(item) : item)),
    }));
  };

  /** 运行结束后刷新事件序号；失败不影响运行结果，保留旧值。 */
  const refreshLastSeq = async (sessionId: string) => {
    try {
      const detail = await sessionApi.detail(sessionId);
      if (get().activeSessionId === sessionId) {
        set({ lastSeq: detail.snapshot.lastSeq });
      }
    } catch {
      // 服务端不可用或接口异常时保持旧序号即可，不弹错误。
    }
  };

  return {
    sessions: [],
    activeSessionId: null,
    transcript: [],
    lastSeq: 0,
    running: false,
    status: 'idle',
    error: null,

    loadSessions: async (force = false) => {
      if (!force && get().status !== 'idle') return;

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
      const requestId = ++detailRequestId;
      set({ activeSessionId: id, lastSeq: 0, error: null });

      try {
        const detail = await sessionApi.detail(id);
        if (requestId !== detailRequestId) return;

        set({
          transcript: transcriptFromSnapshot(detail.snapshot),
          lastSeq: detail.snapshot.lastSeq,
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
          // 只代表模型这一轮结束；工具执行与审批可能仍在继续，
          // 因此 running 要等 SSE 流关闭后再复位（见下方的 finally）。
          updateItem(reply.id, (item) =>
            item.kind === 'message' ? { ...item, streaming: false } : item,
          );
        },
        onError: (message) => {
          updateItem(reply.id, (item) =>
            item.kind === 'message'
              ? { ...item, content: `运行失败：${message}`, streaming: false }
              : item,
          );
          set({ running: false });
        },
      })
        .catch(() => {
          updateItem(reply.id, (item) =>
            item.kind === 'message' ? { ...item, streaming: false } : item,
          );
        })
        .finally(() => {
          // 服务端关闭响应 = 本次运行真正结束，此时才允许再次发送。
          set({ running: false });
          void refreshLastSeq(sessionId);
        });
    },

    cancelRun: () => {
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
      if (sessionId) {
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
