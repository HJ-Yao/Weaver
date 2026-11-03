import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import type { SessionEventDto } from '@/shared/api/sessions';
import { ReplayPanel } from './ReplayPanel';

const { eventsMock } = vi.hoisted(() => ({
  eventsMock: vi.fn(),
}));

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    events: eventsMock,
  },
}));

const realEvents: SessionEventDto[] = [
  {
    seq: 10,
    type: 'USER_MESSAGE',
    role: 'USER',
    toolName: null,
    callId: null,
    approved: null,
    aborted: null,
  },
  {
    seq: 20,
    type: 'TOOL_STARTED',
    role: null,
    toolName: 'file',
    callId: 'call-file-123456',
    approved: null,
    aborted: null,
  },
  {
    seq: 30,
    type: 'TOOL_COMPLETED',
    role: null,
    toolName: null,
    callId: 'call-file-123456',
    approved: null,
    aborted: null,
  },
  {
    seq: 40,
    type: 'APPROVAL_RESOLVED',
    role: null,
    toolName: null,
    callId: 'call-file-123456',
    approved: true,
    aborted: null,
  },
  {
    seq: 50,
    type: 'ASSISTANT_MESSAGE',
    role: 'ASSISTANT',
    toolName: null,
    callId: null,
    approved: null,
    aborted: null,
  },
  {
    seq: 60,
    type: 'TOOL_MESSAGE',
    role: 'TOOL',
    toolName: null,
    callId: null,
    approved: null,
    aborted: null,
  },
  {
    seq: 70,
    type: 'APPROVAL_REQUESTED',
    role: null,
    toolName: 'git.commit',
    callId: 'call-approval',
    approved: null,
    aborted: null,
  },
  {
    seq: 80,
    type: 'TOOL_COMPLETED',
    role: null,
    toolName: null,
    callId: 'deadbeef-1234',
    approved: null,
    aborted: null,
  },
  {
    seq: 90,
    type: 'APPROVAL_RESOLVED',
    role: null,
    toolName: null,
    callId: 'call-approval',
    approved: false,
    aborted: null,
  },
  {
    seq: 100,
    type: 'RUN_FINISHED',
    role: null,
    toolName: null,
    callId: null,
    approved: null,
    aborted: false,
  },
  {
    seq: 110,
    type: 'RUN_FINISHED',
    role: null,
    toolName: null,
    callId: null,
    approved: null,
    aborted: true,
  },
];

const shellEvents: SessionEventDto[] = [
  {
    ...realEvents[1],
    seq: 50,
    toolName: 'shell',
    callId: 'call-shell',
  },
];

beforeEach(() => {
  eventsMock.mockReset();
  useSessionStore.setState({ activeSessionId: 'session-1' });
});

describe('ReplayPanel', () => {
  it('renders labels from the session event API', async () => {
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    expect(await screen.findByRole('button', { name: '用户消息' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '工具开始 · file' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '工具完成 · file' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '审批结果 · 已批准' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '助手消息' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '工具消息' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '审批请求 · git.commit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '工具完成 · deadbeef' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '审批结果 · 已拒绝' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '运行结束 · 完成' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '运行结束 · 已中止' })).toBeInTheDocument();
  });

  it('advances through real events and supports jumping by sequence', async () => {
    const user = userEvent.setup();
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    expect(await screen.findByText('事件 1 / 11')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '下一步' }));

    expect(screen.getByText('事件 2 / 11')).toBeInTheDocument();
    const currentEvent = screen.getByRole('button', { name: '工具开始 · file' });
    expect(currentEvent).toHaveAttribute('aria-current', 'step');

    await user.click(screen.getByRole('button', { name: '审批结果 · 已批准' }));

    expect(screen.getByText('事件 4 / 11')).toBeInTheDocument();
  });

  it('shows the empty state when the session has no events', async () => {
    eventsMock.mockResolvedValue([]);
    render(<ReplayPanel />);

    expect(await screen.findByText('这个会话还没有事件')).toBeInTheDocument();
  });

  it('shows an error and retries the event request', async () => {
    const user = userEvent.setup();
    eventsMock.mockRejectedValueOnce(new Error('offline'));
    eventsMock.mockResolvedValueOnce(realEvents);
    render(<ReplayPanel />);

    expect(await screen.findByText('事件加载失败：offline')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('button', { name: '用户消息' })).toBeInTheDocument();
    expect(eventsMock).toHaveBeenCalledTimes(2);
  });

  it('reloads the timeline when the active session changes', async () => {
    eventsMock.mockResolvedValueOnce([realEvents[0]]);
    eventsMock.mockResolvedValueOnce(shellEvents);
    render(<ReplayPanel />);

    expect(await screen.findByRole('button', { name: '用户消息' })).toBeInTheDocument();

    act(() => {
      useSessionStore.setState({ activeSessionId: 'session-2' });
    });

    expect(await screen.findByRole('button', { name: '工具开始 · shell' })).toBeInTheDocument();
    expect(eventsMock).toHaveBeenNthCalledWith(1, 'session-1');
    expect(eventsMock).toHaveBeenNthCalledWith(2, 'session-2');
  });
});
