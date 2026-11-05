import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  InboxEntryDto,
  SessionDetailDto,
  SessionSnapshotDto,
  SessionSummaryDto,
} from '@/shared/api/sessions';
import { inboxApi, sessionApi } from '@/shared/api/sessions';
import type { StreamHandlers } from '@/shared/api/stream';
import { streamMessage } from '@/shared/api/stream';
import { transcriptFromSnapshot } from './model';
import { useSessionStore } from './store';

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    list: vi.fn(),
    create: vi.fn(),
    detail: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    deleteTurn: vi.fn(),
    resolveApproval: vi.fn(),
    cancelRun: vi.fn(),
  },
  inboxApi: {
    enqueue: vi.fn(),
    list: vi.fn(),
    edit: vi.fn(),
    remove: vi.fn(),
  },
}));

vi.mock('@/shared/api/stream', () => ({
  streamMessage: vi.fn(),
}));

const mockedSessionApi = vi.mocked(sessionApi);
const mockedInboxApi = vi.mocked(inboxApi);
const mockedStreamMessage = vi.mocked(streamMessage);
const initialSessionState = useSessionStore.getState();

const emptySnapshot: SessionSnapshotDto = {
  messages: [],
  toolCalls: [],
  approvals: [],
  aborted: false,
  lastSeq: 0,
};

const firstSession: SessionSummaryDto = {
  id: 'session-1',
  title: 'First',
  createdAt: '2026-10-31T10:00:00Z',
};

const secondSession: SessionSummaryDto = {
  id: 'session-2',
  title: 'Second',
  createdAt: '2026-10-31T11:00:00Z',
};

function detailFor(summary: SessionSummaryDto, content = ''): SessionDetailDto {
  return {
    summary,
    snapshot: {
      ...emptySnapshot,
      messages: content ? [{ role: 'ASSISTANT', content, seq: 1 }] : [],
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  useSessionStore.setState({
    sessions: [],
    activeSessionId: null,
    transcript: [],
    lastSeq: 0,
    status: 'idle',
    error: null,
    running: false,
    pending: [],
  });
  mockedInboxApi.list.mockResolvedValue([]);
});

describe('useSessionStore', () => {
  it('starts with an empty real-data state', () => {
    expect(initialSessionState).toMatchObject({
      sessions: [],
      activeSessionId: null,
      transcript: [],
      lastSeq: 0,
      running: false,
      status: 'idle',
      error: null,
      pending: [],
    });
  });

  it('loads sessions and selects the first session', async () => {
    mockedSessionApi.list.mockResolvedValue([firstSession, secondSession]);
    mockedSessionApi.detail.mockResolvedValue(detailFor(firstSession, 'first transcript'));

    await useSessionStore.getState().loadSessions();

    const state = useSessionStore.getState();
    expect(state.sessions).toEqual([firstSession, secondSession]);
    expect(state.activeSessionId).toBe(firstSession.id);
    expect(state.transcript).toEqual(
      transcriptFromSnapshot(detailFor(firstSession, 'first transcript').snapshot),
    );
    expect(state.status).toBe('ready');
    expect(mockedSessionApi.detail).toHaveBeenCalledWith(firstSession.id);
  });

  it('keeps the session list empty and reports a readable error when loading fails', async () => {
    mockedSessionApi.list.mockRejectedValue(new Error('offline'));

    await useSessionStore.getState().loadSessions();

    const state = useSessionStore.getState();
    expect(state.status).toBe('error');
    expect(state.error).toContain('会话列表加载失败');
    expect(state.error).toContain('offline');
    expect(state.sessions).toEqual([]);
  });

  it('rebuilds the transcript from the selected session detail', async () => {
    const detail = detailFor(firstSession, 'loaded transcript');
    mockedSessionApi.detail.mockResolvedValue(detail);

    await useSessionStore.getState().selectSession(firstSession.id);

    const state = useSessionStore.getState();
    expect(state.activeSessionId).toBe(firstSession.id);
    expect(state.transcript).toEqual(transcriptFromSnapshot(detail.snapshot));
    expect(state.status).toBe('ready');
  });

  it('ignores an older detail response when sessions are switched quickly', async () => {
    let resolveSlowRequest: (detail: SessionDetailDto) => void = () => undefined;
    const slowRequest = new Promise<SessionDetailDto>((resolve) => {
      resolveSlowRequest = resolve;
    });

    mockedSessionApi.detail.mockImplementation((id) =>
      id === firstSession.id
        ? slowRequest
        : Promise.resolve(detailFor(secondSession, 'latest transcript')),
    );

    const selectSlowSession = useSessionStore.getState().selectSession(firstSession.id);
    const selectFastSession = useSessionStore.getState().selectSession(secondSession.id);

    await selectFastSession;
    resolveSlowRequest(detailFor(firstSession, 'stale transcript'));
    await selectSlowSession;

    const state = useSessionStore.getState();
    expect(state.activeSessionId).toBe(secondSession.id);
    expect(state.transcript).toEqual(
      transcriptFromSnapshot(detailFor(secondSession, 'latest transcript').snapshot),
    );
  });

  it('appends a created session and selects it with an empty transcript', async () => {
    const createdSession: SessionSummaryDto = {
      id: 'session-created',
      title: 'Created',
      createdAt: '2026-10-31T12:00:00Z',
    };
    mockedSessionApi.create.mockResolvedValue(createdSession);

    await useSessionStore.getState().createSession();

    const state = useSessionStore.getState();
    expect(mockedSessionApi.create).toHaveBeenCalledWith();
    expect(state.sessions.at(-1)).toEqual(createdSession);
    expect(state.activeSessionId).toBe(createdSession.id);
    expect(state.transcript).toEqual([]);
  });

  it('renames a session in place after the server accepts it', async () => {
    const renamed = { ...firstSession, title: 'Renamed' };
    mockedSessionApi.rename.mockResolvedValue(renamed);
    useSessionStore.setState({ sessions: [firstSession, secondSession] });

    await expect(
      useSessionStore.getState().renameSession(firstSession.id, 'Renamed'),
    ).resolves.toBe(true);

    expect(mockedSessionApi.rename).toHaveBeenCalledWith(firstSession.id, 'Renamed');
    expect(useSessionStore.getState().sessions).toEqual([renamed, secondSession]);
  });

  it('deletes the current session and selects the next one', async () => {
    mockedSessionApi.remove.mockResolvedValue(undefined);
    mockedSessionApi.detail.mockResolvedValue(detailFor(secondSession, 'second transcript'));
    useSessionStore.setState({
      sessions: [firstSession, secondSession],
      activeSessionId: firstSession.id,
      transcript: transcriptFromSnapshot(detailFor(firstSession, 'old transcript').snapshot),
      lastSeq: 3,
      pending: [{ id: 'in-old', target: 'STEP', content: 'old' }],
    });

    await expect(useSessionStore.getState().deleteSession(firstSession.id)).resolves.toBe(true);

    const state = useSessionStore.getState();
    expect(mockedSessionApi.remove).toHaveBeenCalledWith(firstSession.id);
    expect(state.sessions).toEqual([secondSession]);
    expect(state.activeSessionId).toBe(secondSession.id);
    expect(state.transcript).toEqual(
      transcriptFromSnapshot(detailFor(secondSession, 'second transcript').snapshot),
    );
  });

  it('clears the active session when deleting the last one', async () => {
    mockedSessionApi.remove.mockResolvedValue(undefined);
    useSessionStore.setState({
      sessions: [firstSession],
      activeSessionId: firstSession.id,
      transcript: transcriptFromSnapshot(detailFor(firstSession, 'last transcript').snapshot),
      lastSeq: 7,
      pending: [{ id: 'in-last', target: 'TURN', content: 'last' }],
    });

    await expect(useSessionStore.getState().deleteSession(firstSession.id)).resolves.toBe(true);

    const state = useSessionStore.getState();
    expect(state.sessions).toEqual([]);
    expect(state.activeSessionId).toBeNull();
    expect(state.transcript).toEqual([]);
    expect(state.lastSeq).toBe(0);
    expect(state.pending).toEqual([]);
  });

  it('reloads the active session after deleting a turn', async () => {
    const reloaded = detailFor(firstSession, 'remaining transcript');
    reloaded.snapshot.lastSeq = 8;
    mockedSessionApi.deleteTurn.mockResolvedValue(undefined);
    mockedSessionApi.detail.mockResolvedValue(reloaded);
    useSessionStore.setState({
      sessions: [firstSession],
      activeSessionId: firstSession.id,
      transcript: transcriptFromSnapshot(
        detailFor(firstSession, 'deleted transcript').snapshot,
      ),
      running: false,
    });

    await expect(useSessionStore.getState().deleteTurn(4)).resolves.toBe(true);

    const state = useSessionStore.getState();
    expect(mockedSessionApi.deleteTurn).toHaveBeenCalledWith(firstSession.id, 4);
    expect(mockedSessionApi.detail).toHaveBeenCalledWith(firstSession.id);
    expect(state.transcript).toEqual(transcriptFromSnapshot(reloaded.snapshot));
    expect(state.lastSeq).toBe(8);
  });

  it('returns false without calling the API when no session is active', async () => {
    useSessionStore.setState({ activeSessionId: null, running: false });

    await expect(useSessionStore.getState().deleteTurn(4)).resolves.toBe(false);

    expect(mockedSessionApi.deleteTurn).not.toHaveBeenCalled();
  });

  it('returns false without calling the API while a run is active', async () => {
    useSessionStore.setState({ activeSessionId: firstSession.id, running: true });

    await expect(useSessionStore.getState().deleteTurn(4)).resolves.toBe(false);

    expect(mockedSessionApi.deleteTurn).not.toHaveBeenCalled();
  });

  it('returns false when deleting a turn fails', async () => {
    mockedSessionApi.deleteTurn.mockRejectedValue(new Error('bad request'));
    useSessionStore.setState({
      activeSessionId: firstSession.id,
      running: false,
      transcript: [],
    });

    await expect(useSessionStore.getState().deleteTurn(4)).resolves.toBe(false);

    expect(mockedSessionApi.detail).not.toHaveBeenCalled();
    expect(useSessionStore.getState().transcript).toEqual([]);
  });

  it('updates the generated title on the matching session', () => {
    let handlers: StreamHandlers | undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>(() => {});
    });
    useSessionStore.setState({
      sessions: [firstSession, secondSession],
      activeSessionId: firstSession.id,
    });

    useSessionStore.getState().sendMessage('name this session');
    handlers?.onSessionTitle?.('自动命名');

    expect(useSessionStore.getState().sessions).toEqual([
      { ...firstSession, title: '自动命名' },
      secondSession,
    ]);
  });

  it('creates a session before streaming the first welcome-page message', async () => {
    const createdSession: SessionSummaryDto = {
      id: 'session-first-message',
      title: '新会话',
      createdAt: '2026-10-31T12:30:00Z',
    };
    let resolveCreate: (session: SessionSummaryDto) => void = () => undefined;
    mockedSessionApi.create.mockReturnValue(
      new Promise<SessionSummaryDto>((resolve) => {
        resolveCreate = resolve;
      }),
    );
    mockedStreamMessage.mockResolvedValue(undefined);

    useSessionStore.getState().sendMessage('  build the welcome page  ');

    expect(mockedSessionApi.create).toHaveBeenCalledWith();
    expect(mockedStreamMessage).not.toHaveBeenCalled();

    resolveCreate(createdSession);

    await vi.waitFor(() =>
      expect(mockedStreamMessage).toHaveBeenCalledWith(
        createdSession.id,
        'build the welcome page',
        expect.any(Object),
      ),
    );

    const state = useSessionStore.getState();
    expect(state.activeSessionId).toBe(createdSession.id);
    expect(
      state.transcript.some(
        (item) =>
          item.kind === 'message' &&
          item.role === 'user' &&
          item.content === 'build the welcome page',
      ),
    ).toBe(true);
  });

  it('keeps the first message out of the run when session creation fails', async () => {
    mockedSessionApi.create.mockRejectedValue(new Error('create failed'));

    useSessionStore.getState().sendMessage('do not lose me');

    await vi.waitFor(() => expect(useSessionStore.getState().status).toBe('error'));
    expect(mockedStreamMessage).not.toHaveBeenCalled();
    expect(useSessionStore.getState().transcript).toEqual([]);
    expect(useSessionStore.getState().error).toContain('create failed');
  });

  it('keeps the run active after the model turn until the stream closes', async () => {
    let handlers: StreamHandlers | undefined;
    let closeStream: () => void = () => undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>((resolve) => {
        closeStream = resolve;
      });
    });
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().sendMessage('x');
    handlers?.onDone?.('STOP');

    // 模型这一轮结束了，但工具/审批可能还在跑，运行状态不能结束。
    expect(useSessionStore.getState().running).toBe(true);

    closeStream();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(useSessionStore.getState().running).toBe(false);
  });

  it('updates tool cards from stream results and ignores unknown calls', () => {
    let handlers: StreamHandlers | undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>(() => {});
    });
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().sendMessage('x');
    handlers?.onTool?.({
      callId: 'call-success',
      toolName: 'file',
      argumentsJson: '{"op":"read"}',
    });
    handlers?.onToolResult?.({
      callId: 'call-success',
      content: 'file contents',
      isError: false,
      durationMs: 125,
    });
    handlers?.onTool?.({
      callId: 'call-failed',
      toolName: 'shell',
      argumentsJson: '{"command":"false"}',
    });
    handlers?.onToolResult?.({
      callId: 'call-failed',
      content: 'exit code 1',
      isError: true,
      durationMs: 250,
    });
    handlers?.onToolResult?.({
      callId: 'call-missing',
      content: 'late result',
      isError: false,
      durationMs: 10,
    });

    const transcript = useSessionStore.getState().transcript;
    expect(transcript.find((item) => item.id === 'call-success')).toMatchObject({
      kind: 'tool',
      status: 'done',
      durationMs: 125,
      resultText: 'file contents',
    });
    expect(transcript.find((item) => item.id === 'call-failed')).toMatchObject({
      kind: 'tool',
      status: 'failed',
      durationMs: 250,
      resultText: 'exit code 1',
    });
    expect(transcript).toHaveLength(4);
  });

  it('requests cancellation for the active real-mode run', () => {
    mockedSessionApi.cancelRun.mockResolvedValue(undefined);
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().cancelRun();

    expect(mockedSessionApi.cancelRun).toHaveBeenCalledWith(firstSession.id);
  });

  it('removes a resolved approval without changing other transcript items', () => {
    mockedSessionApi.resolveApproval.mockResolvedValue(undefined);
    const userMessage = {
      kind: 'message' as const,
      id: 'message-user',
      role: 'user' as const,
      content: 'commit this',
    };
    const pendingApproval = {
      kind: 'approval' as const,
      id: 'call-approval',
      toolName: 'git.commit',
      argumentsJson: '{"message":"test"}',
      reversibility: 'IRREVERSIBLE' as const,
      timeoutSeconds: 120,
      status: 'pending' as const,
    };
    const toolCall = {
      kind: 'tool' as const,
      id: 'call-tool',
      toolName: 'file.read',
      argumentsJson: '{"path":"README.md"}',
      status: 'done' as const,
      expanded: false,
    };
    useSessionStore.setState({
      activeSessionId: firstSession.id,
      transcript: [userMessage, pendingApproval, toolCall],
    });

    useSessionStore.getState().resolveApproval(pendingApproval.id, true);

    expect(mockedSessionApi.resolveApproval).toHaveBeenCalledWith(
      firstSession.id,
      pendingApproval.id,
      true,
    );
    expect(useSessionStore.getState().transcript).toEqual([userMessage, toolCall]);
  });

  it('marks the streaming reply stopped when the server reports an abort', () => {
    let handlers: StreamHandlers | undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>(() => {});
    });
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().sendMessage('x');
    handlers?.onDelta?.('partial');
    handlers?.onAborted?.('cancelled');

    const state = useSessionStore.getState();
    const reply = state.transcript.find(
      (item) => item.kind === 'message' && item.role === 'assistant',
    );
    expect(reply).toMatchObject({
      content: 'partial',
      streaming: false,
      stopped: true,
    });
    expect(state.running).toBe(false);
  });

  it('appends an enqueued inbox entry after the server accepts it', async () => {
    const entry: InboxEntryDto = {
      id: 'in-1',
      target: 'TURN',
      content: '跑完更新 README',
    };
    mockedInboxApi.enqueue.mockResolvedValue(entry);
    useSessionStore.setState({ activeSessionId: firstSession.id });

    await expect(
      useSessionStore.getState().enqueueMessage(entry.content, entry.target),
    ).resolves.toBe(true);

    expect(mockedInboxApi.enqueue).toHaveBeenCalledWith(
      firstSession.id,
      entry.content,
      entry.target,
    );
    expect(useSessionStore.getState().pending).toEqual([entry]);
  });

  it('returns false without starting a run when inbox delivery is rejected', async () => {
    mockedInboxApi.enqueue.mockRejectedValue(new Error('run is no longer active'));
    useSessionStore.setState({ activeSessionId: firstSession.id });

    await expect(useSessionStore.getState().enqueueMessage('keep me', 'TURN')).resolves.toBe(false);

    expect(useSessionStore.getState().pending).toEqual([]);
    expect(useSessionStore.getState().running).toBe(false);
    expect(mockedStreamMessage).not.toHaveBeenCalled();
  });

  it('loads pending inbox entries when selecting a session', async () => {
    const entry: InboxEntryDto = {
      id: 'in-restored',
      target: 'STEP',
      content: '只改 main',
    };
    mockedSessionApi.detail.mockResolvedValue(detailFor(firstSession));
    mockedInboxApi.list.mockResolvedValue([entry]);

    await useSessionStore.getState().selectSession(firstSession.id);

    expect(mockedInboxApi.list).toHaveBeenCalledWith(firstSession.id);
    expect(useSessionStore.getState().pending).toEqual([entry]);
  });

  it('deduplicates the optimistic user message but appends later injections', () => {
    let handlers: StreamHandlers | undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>(() => {});
    });
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().sendMessage('queued turn');
    handlers?.onUserMessage?.('queued turn');
    handlers?.onUserMessage?.('steer this step');

    const userMessages = useSessionStore
      .getState()
      .transcript.filter((item) => item.kind === 'message' && item.role === 'user')
      .flatMap((item) => (item.kind === 'message' ? [item.content] : []));
    expect(userMessages).toEqual(['queued turn', 'steer this step']);
  });

  it('ignores stream callbacks from a session that is no longer active', async () => {
    let handlers: StreamHandlers | undefined;
    mockedStreamMessage.mockImplementation((_sessionId, _content, streamHandlers) => {
      handlers = streamHandlers;
      return new Promise<void>(() => {});
    });
    mockedSessionApi.detail.mockImplementation(async (id) =>
      detailFor(id === secondSession.id ? secondSession : firstSession),
    );
    mockedInboxApi.list.mockResolvedValue([]);
    useSessionStore.setState({ activeSessionId: firstSession.id });

    useSessionStore.getState().sendMessage('first run');
    await useSessionStore.getState().selectSession(secondSession.id);

    handlers?.onUserMessage?.('steer from the old run');
    handlers?.onInbox?.([{ id: 'in-old', target: 'STEP', content: 'old' }]);

    expect(useSessionStore.getState().pending).toEqual([]);
    expect(
      useSessionStore
        .getState()
        .transcript.filter((item) => item.kind === 'message')
        .map((item) => (item.kind === 'message' ? item.content : '')),
    ).not.toContain('steer from the old run');
  });

  it('clears pending entries when the active stream closes', async () => {
    let closeStream: () => void = () => undefined;
    mockedStreamMessage.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          closeStream = resolve;
        }),
    );
    useSessionStore.setState({
      activeSessionId: firstSession.id,
      pending: [{ id: 'in-stale', target: 'TURN', content: 'stale' }],
    });

    useSessionStore.getState().sendMessage('run');
    closeStream();

    await vi.waitFor(() => expect(useSessionStore.getState().running).toBe(false));
    expect(useSessionStore.getState().pending).toEqual([]);
  });

  it('restores pending entries from the server when removing an entry fails', async () => {
    const entries: InboxEntryDto[] = [
      { id: 'in-1', target: 'STEP', content: 'first' },
      { id: 'in-2', target: 'TURN', content: 'second' },
    ];
    mockedInboxApi.remove.mockRejectedValue(new Error('already consumed'));
    mockedInboxApi.list.mockResolvedValue(entries);
    useSessionStore.setState({ activeSessionId: firstSession.id, pending: entries });

    useSessionStore.getState().removePending('in-1');

    expect(useSessionStore.getState().pending).toEqual([entries[1]]);
    await vi.waitFor(() => expect(mockedInboxApi.list).toHaveBeenCalledWith(firstSession.id));
    await vi.waitFor(() => expect(useSessionStore.getState().pending).toEqual(entries));
  });

  it('restores pending entries from the server when editing an entry fails', async () => {
    const entries: InboxEntryDto[] = [{ id: 'in-1', target: 'STEP', content: 'first' }];
    mockedInboxApi.edit.mockRejectedValue(new Error('already consumed'));
    mockedInboxApi.list.mockResolvedValue(entries);
    useSessionStore.setState({ activeSessionId: firstSession.id, pending: entries });

    useSessionStore.getState().editPending('in-1', 'changed');

    expect(useSessionStore.getState().pending).toEqual([{ ...entries[0], content: 'changed' }]);
    await vi.waitFor(() => expect(mockedInboxApi.list).toHaveBeenCalledWith(firstSession.id));
    await vi.waitFor(() => expect(useSessionStore.getState().pending).toEqual(entries));
  });
});
