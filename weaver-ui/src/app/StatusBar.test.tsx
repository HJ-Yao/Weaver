import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionDetailDto, SessionSummaryDto } from '@/shared/api/sessions';
import { sessionApi } from '@/shared/api/sessions';
import type { ServerStatusDto } from '@/shared/api/status';
import { statusApi } from '@/shared/api/status';
import { streamMessage } from '@/shared/api/stream';
import { useUiStore } from './ui-store';

vi.mock('@/shared/api/status', () => ({
  statusApi: {
    get: vi.fn(),
  },
}));

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    detail: vi.fn(),
  },
}));

vi.mock('@/shared/api/stream', () => ({
  streamMessage: vi.fn(),
}));

// store 在模块加载时读取运行模式，必须先固定真实模式，再导入单例。
vi.stubEnv('VITE_USE_MOCK', 'false');
const { useSessionStore } = await import('@/entities/session/store');
const { StatusBar } = await import('./StatusBar');

const mockedStatusApi = vi.mocked(statusApi);
const mockedSessionApi = vi.mocked(sessionApi);
const mockedStreamMessage = vi.mocked(streamMessage);

const connectedStatus: ServerStatusDto = {
  status: 'ok',
  storage: 'sqlite',
  wal: true,
  workspace: 'F:\\workspace',
  maxSteps: 50,
  uptimeSeconds: 12,
};

const summary: SessionSummaryDto = {
  id: 'session-1',
  title: 'Status test',
  createdAt: '2026-11-01T08:00:00Z',
};

function detailWith(lastSeq: number, message = 'snapshot message'): SessionDetailDto {
  return {
    summary,
    snapshot: {
      messages: [{ role: 'ASSISTANT', content: message }],
      toolCalls: [],
      approvals: [],
      aborted: false,
      lastSeq,
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
    running: false,
    status: 'idle',
    error: null,
  });
  useUiStore.setState({ approvalMode: 'DEFAULT' });
});

describe('StatusBar', () => {
  it('shows live connection, storage, and session sequence values', async () => {
    mockedStatusApi.get.mockResolvedValue(connectedStatus);
    useSessionStore.setState({ lastSeq: 42 });

    render(<StatusBar />);

    expect(await screen.findByText('已连接')).toBeInTheDocument();
    expect(screen.getByText('sqlite · WAL')).toBeInTheDocument();
    expect(screen.getByText('seq 42')).toBeInTheDocument();
    expect(screen.getByText('tokens —')).toHaveAttribute('title', '模型用量统计尚未接入（M2）');
  });

  it('shows a disconnected state and hides unavailable storage details on failure', async () => {
    mockedStatusApi.get.mockRejectedValue(new Error('service unavailable'));

    render(<StatusBar />);

    const connection = await screen.findByText('未连接');
    expect(connection).toHaveAttribute('title', 'service unavailable');
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('shows the connecting state while the initial status request is pending', () => {
    mockedStatusApi.get.mockReturnValue(new Promise<ServerStatusDto>(() => undefined));

    const { unmount } = render(<StatusBar />);

    expect(screen.getByText('连接中…')).toBeInTheDocument();
    unmount();
  });
});

describe('session last sequence', () => {
  it('writes lastSeq from a successfully selected session detail', async () => {
    mockedSessionApi.detail.mockResolvedValue(detailWith(27));

    await useSessionStore.getState().selectSession(summary.id);

    expect(useSessionStore.getState().lastSeq).toBe(27);
  });

  it('refreshes lastSeq after a run without rebuilding the transcript', async () => {
    mockedStreamMessage.mockResolvedValue(undefined);
    mockedSessionApi.detail.mockResolvedValue(detailWith(31, 'rebuilt transcript'));
    useSessionStore.setState({
      activeSessionId: summary.id,
      transcript: [
        {
          kind: 'tool',
          id: 'call-1',
          toolName: 'shell',
          argumentsJson: '{"command":"pwd"}',
          status: 'done',
          expanded: false,
        },
      ],
    });

    useSessionStore.getState().sendMessage('continue');

    await waitFor(() => expect(useSessionStore.getState().lastSeq).toBe(31));
    expect(
      useSessionStore
        .getState()
        .transcript.some(
          (item) => item.kind === 'message' && item.content === 'rebuilt transcript',
        ),
    ).toBe(false);
    expect(
      useSessionStore
        .getState()
        .transcript.some((item) => item.kind === 'tool' && item.id === 'call-1'),
    ).toBe(true);
  });
});
