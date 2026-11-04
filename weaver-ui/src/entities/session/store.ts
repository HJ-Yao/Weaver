import { create } from 'zustand';
import { inboxApi, sessionApi } from '@/shared/api/sessions';
import type { InboxEntryDto, InboxTargetDto } from '@/shared/api/sessions';
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
  /** 运行中待发送的输入，按到达顺序。 */
  pending: InboxEntryDto[];
  /** 当前会话快照中最后一条事件序号；尚未加载时为 `0`。 */
  lastSeq: number;
  /** 运行期间为 `true`；输入区仍可排队或插话。 */
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
  /** 运行中投递一条输入；成功返回 `true`。没有活动运行时返回 `false`，由界面提示。 */
  enqueueMessage: (content: string, target: InboxTargetDto) => Promise<boolean>;
  /** 撤回一条待发送输入。 */
  removePending: (id: string) => void;
  /** 修改一条待发送输入。 */
  editPending: (id: string, content: string) => void;
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

  /** 乐观操作失败后重新拉取服务端快照，避免界面与信箱实际状态分叉。 */
  const restorePending = async (sessionId: string) => {
    try {
      const entries = await inboxApi.list(sessionId);
      if (get().activeSessionId === sessionId) {
        set({ pending: entries });
      }
    } catch {
      // 运行可能已结束且服务端不可达；保留界面当前结果，等待下一次选择会话时恢复。
    }
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

  /**
   * 确保当前有会话后再追加消息并启动流。
   *
   * 首屏发送时 `createSession` 会清空 transcript，因此必须等它完成后
   * 才写入用户消息；创建失败时保留 `createSession` 写入的错误状态。
   */
  const startSend = async (text: string) => {
    let sessionId = get().activeSessionId;
    if (!sessionId) {
      await get().createSession();
      sessionId = get().activeSessionId;
      if (!sessionId) return;
    }

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

    let initialEchoed = false;
    // 运行期间切换会话时，旧运行的流仍在推送；只让当前会话消费这些事件。
    const isCurrentSession = () => get().activeSessionId === sessionId;
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
      onInbox: (entries) => {
        if (isCurrentSession()) set({ pending: entries });
      },
      onUserMessage: (messageText) => {
        if (!isCurrentSession()) return;
        // 首条回显只用于确认本次已落库；相同文本不再叠加到乐观显示的用户消息上。
        if (!initialEchoed && messageText === text) {
          initialEchoed = true;
          return;
        }
        initialEchoed = true;
        const message: MessageItem = {
          kind: 'message',
          id: nextId('message'),
          role: 'user',
          content: messageText,
        };
        set((state) => ({ transcript: [...state.transcript, message] }));
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
        set({ running: false, pending: [] });
        void refreshLastSeq(sessionId);
      });
  };

  return {
    sessions: [],
    activeSessionId: null,
    transcript: [],
    pending: [],
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
      set({ activeSessionId: id, lastSeq: 0, pending: [], error: null });

      try {
        const detail = await sessionApi.detail(id);
        if (requestId !== detailRequestId) return;

        set({
          transcript: transcriptFromSnapshot(detail.snapshot),
          lastSeq: detail.snapshot.lastSeq,
          status: 'ready',
          error: null,
        });

        try {
          const entries = await inboxApi.list(id);
          if (requestId === detailRequestId && get().activeSessionId === id) {
            set({ pending: entries });
          }
        } catch {
          // 没有活动运行或信箱暂不可读时保持空队列，不能让详情加载因此失败。
        }
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
          pending: [],
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
      void startSend(text);
    },

    enqueueMessage: async (content, target) => {
      const sessionId = get().activeSessionId;
      const text = content.trim();
      if (!sessionId || !text) return false;

      try {
        const entry = await inboxApi.enqueue(sessionId, text, target);
        if (get().activeSessionId === sessionId) {
          set((state) => ({
            pending: state.pending.some((item) => item.id === entry.id)
              ? state.pending
              : [...state.pending, entry],
          }));
        }
        return true;
      } catch {
        return false;
      }
    },

    removePending: (id) => {
      const sessionId = get().activeSessionId;
      if (!sessionId || !get().pending.some((entry) => entry.id === id)) return;

      set((state) => ({ pending: state.pending.filter((entry) => entry.id !== id) }));
      void inboxApi.remove(sessionId, id).catch(() => restorePending(sessionId));
    },

    editPending: (id, content) => {
      const sessionId = get().activeSessionId;
      const text = content.trim();
      if (!sessionId || !text) return;

      set((state) => ({
        pending: state.pending.map((entry) =>
          entry.id === id ? { ...entry, content: text } : entry,
        ),
      }));
      void inboxApi
        .edit(sessionId, id, text)
        .then((entry) => {
          if (get().activeSessionId === sessionId) {
            set((state) => ({
              pending: state.pending.map((item) => (item.id === id ? entry : item)),
            }));
          }
        })
        .catch(() => restorePending(sessionId));
    },

    cancelRun: () => {
      const sessionId = get().activeSessionId;
      if (sessionId) {
        void sessionApi.cancelRun(sessionId).catch(() => undefined);
      }
    },

    resolveApproval: (callId, approved) => {
      set((state) => ({
        transcript: state.transcript.filter(
          (item) => !(item.kind === 'approval' && item.id === callId),
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
