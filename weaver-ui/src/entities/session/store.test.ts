import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  SessionDetailDto,
  SessionSnapshotDto,
  SessionSummaryDto,
} from '@/shared/api/sessions';
import { sessionApi } from '@/shared/api/sessions';
import { transcriptFromSnapshot } from './model';

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    list: vi.fn(),
    create: vi.fn(),
    detail: vi.fn(),
    remove: vi.fn(),
    resolveApproval: vi.fn(),
  },
}));

// The store reads the mode once at module load, so fix the environment before
// dynamically importing the real-mode singleton.
vi.stubEnv('VITE_USE_MOCK', 'false');
const { useSessionStore } = await import('./store');

const mockedSessionApi = vi.mocked(sessionApi);

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
      messages: content ? [{ role: 'ASSISTANT', content }] : [],
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  useSessionStore.setState({
    sessions: [],
    activeSessionId: null,
    transcript: [],
    status: 'idle',
    error: null,
    running: false,
  });
});

describe('useSessionStore real API mode', () => {
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

    await useSessionStore.getState().createSession(createdSession.title);

    const state = useSessionStore.getState();
    expect(mockedSessionApi.create).toHaveBeenCalledWith(createdSession.title);
    expect(state.sessions.at(-1)).toEqual(createdSession);
    expect(state.activeSessionId).toBe(createdSession.id);
    expect(state.transcript).toEqual([]);
  });
});
