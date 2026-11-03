import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import type { SessionDetailDto, SessionSummaryDto } from '@/shared/api/sessions';
import { sessionApi } from '@/shared/api/sessions';
import type { ServerStatusDto } from '@/shared/api/status';
import { statusApi } from '@/shared/api/status';
import { streamMessage } from '@/shared/api/stream';
import { workspaceApi } from '@/shared/api/workspace';
import { routes } from './router';
import { useServerStatusStore } from './server-status-store';

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    list: vi.fn(),
    create: vi.fn(),
    detail: vi.fn(),
    remove: vi.fn(),
    resolveApproval: vi.fn(),
    cancelRun: vi.fn(),
  },
}));

vi.mock('@/shared/api/stream', () => ({
  streamMessage: vi.fn(),
}));

vi.mock('@/shared/api/status', () => ({
  statusApi: {
    get: vi.fn(),
  },
}));

vi.mock('@/shared/api/workspace', () => ({
  workspaceApi: {
    diff: vi.fn(),
  },
}));

const mockedSessionApi = vi.mocked(sessionApi);
const mockedStatusApi = vi.mocked(statusApi);
const mockedStreamMessage = vi.mocked(streamMessage);
const mockedWorkspaceApi = vi.mocked(workspaceApi);

const connectedStatus: ServerStatusDto = {
  status: 'ok',
  storage: 'sqlite',
  wal: true,
  workspace: 'F:\\workspace',
  maxSteps: 20,
  uptimeSeconds: 12,
  model: 'deepseek-chat',
  protocol: 'openai-chat-completions',
};

const loadedSession: SessionSummaryDto = {
  id: 'session-live',
  title: '真实会话',
  createdAt: '2026-11-02T08:00:00Z',
};

const loadedDetail: SessionDetailDto = {
  summary: loadedSession,
  snapshot: {
    messages: [
      { role: 'USER', content: '从后端加载的问题' },
      { role: 'ASSISTANT', content: '从后端加载的回答' },
    ],
    toolCalls: [],
    approvals: [],
    aborted: false,
    lastSeq: 2,
  },
};

function renderApp(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
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
  useServerStatusStore.setState({ phase: 'connecting', status: null, error: null });
  mockedSessionApi.list.mockResolvedValue([]);
  mockedStatusApi.get.mockResolvedValue(connectedStatus);
  mockedWorkspaceApi.diff.mockResolvedValue({ files: [], truncated: false });
});

describe('app shell', () => {
  it('loads sessions and their transcript from the API on first render', async () => {
    mockedSessionApi.list.mockResolvedValue([loadedSession]);
    mockedSessionApi.detail.mockResolvedValue(loadedDetail);

    renderApp();

    expect(await screen.findByRole('heading', { name: loadedSession.title })).toBeInTheDocument();
    expect(await screen.findByText('从后端加载的问题')).toBeInTheDocument();
    expect(screen.getByText('从后端加载的回答')).toBeInTheDocument();
    expect(mockedSessionApi.list).toHaveBeenCalledTimes(1);
    expect(mockedSessionApi.detail).toHaveBeenCalledWith(loadedSession.id);
  });

  it('sends the entered message through the stream API and shows the user bubble', async () => {
    const user = userEvent.setup();
    mockedSessionApi.list.mockResolvedValue([loadedSession]);
    mockedSessionApi.detail.mockResolvedValue(loadedDetail);
    mockedStreamMessage.mockResolvedValue(undefined);

    renderApp();

    const input = await screen.findByLabelText('消息输入框');
    await user.type(input, 'hello');
    await user.keyboard('{Enter}');

    expect(screen.getByText('hello')).toBeInTheDocument();
    await waitFor(() =>
      expect(mockedStreamMessage).toHaveBeenCalledWith(
        loadedSession.id,
        'hello',
        expect.any(Object),
      ),
    );
    expect(input).toHaveValue('');
  });

  it('switches the plugin catalogue through the sidebar tab', async () => {
    const user = userEvent.setup();
    renderApp('/plugins');

    expect(screen.getByRole('heading', { name: '插件' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /技能/ }));

    expect(screen.getByRole('heading', { name: '技能' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '＋ 添加技能' })).toBeInTheDocument();
  });

  it('opens the settings page from the rail', async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(screen.getByRole('link', { name: '设置' }));

    expect(screen.getByRole('heading', { name: '设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '外观' })).toBeInTheDocument();
  });
});
