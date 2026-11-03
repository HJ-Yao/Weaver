import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import type { SessionEventDto } from '@/shared/api/sessions';
import { ReplayPanel } from './ReplayPanel';
import { groupTurns } from './replayTurns';

const { eventsMock } = vi.hoisted(() => ({
  eventsMock: vi.fn(),
}));

vi.mock('@/shared/api/sessions', () => ({
  sessionApi: {
    events: eventsMock,
  },
}));

function event(
  overrides: Partial<SessionEventDto> & Pick<SessionEventDto, 'seq' | 'type'>,
): SessionEventDto {
  return {
    role: null,
    toolName: null,
    callId: null,
    approved: null,
    aborted: null,
    content: null,
    argumentsJson: null,
    resultContent: null,
    resultError: null,
    reason: null,
    ...overrides,
  };
}

const realEvents: SessionEventDto[] = [
  event({
    seq: 10,
    type: 'USER_MESSAGE',
    role: 'USER',
    content: '请检查 README 并总结项目结构',
  }),
  event({
    seq: 20,
    type: 'TOOL_STARTED',
    toolName: 'file',
    callId: 'call-file-123456',
    argumentsJson: '{"path":"README.md","mode":"read"}',
  }),
  event({
    seq: 30,
    type: 'TOOL_COMPLETED',
    callId: 'call-file-123456',
    resultContent: '# Weaver\n\nJava Agent framework',
    resultError: true,
  }),
  event({
    seq: 40,
    type: 'APPROVAL_RESOLVED',
    callId: 'call-file-123456',
    approved: true,
    reason: '用户已批准读取文件',
  }),
  event({
    seq: 50,
    type: 'ASSISTANT_MESSAGE',
    role: 'ASSISTANT',
    content: '项目由 core、server 和 ui 组成',
  }),
  event({
    seq: 60,
    type: 'TOOL_MESSAGE',
    role: 'TOOL',
    content: '工具返回结果',
  }),
  event({
    seq: 70,
    type: 'APPROVAL_REQUESTED',
    toolName: 'git.commit',
    callId: 'call-approval',
  }),
  event({
    seq: 80,
    type: 'TOOL_COMPLETED',
    callId: 'deadbeef-1234',
    resultContent: 'command failed',
    resultError: false,
  }),
  event({
    seq: 90,
    type: 'APPROVAL_RESOLVED',
    callId: 'call-approval',
    approved: false,
    reason: '用户拒绝了提交',
  }),
  event({
    seq: 100,
    type: 'RUN_FINISHED',
    aborted: false,
  }),
  event({
    seq: 110,
    type: 'RUN_FINISHED',
    aborted: true,
  }),
];

const shellEvents: SessionEventDto[] = [
  event({
    ...realEvents[1],
    seq: 50,
    toolName: 'shell',
    callId: 'call-shell',
  }),
];

beforeEach(() => {
  eventsMock.mockReset();
  useSessionStore.setState({ activeSessionId: 'session-1' });
});

describe('ReplayPanel', () => {
  it('renders labels with previews from the session event API', async () => {
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    expect(
      await screen.findByRole('button', { name: /用户消息 · 请检查 README/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '工具开始 · file' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /工具完成 · file.*失败/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '审批结果 · 已批准' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /助手消息 · 项目由/ })).toBeInTheDocument();
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

    expect(
      await screen.findByRole('button', { name: /用户消息 · 请检查 README/ }),
    ).toBeInTheDocument();
    expect(eventsMock).toHaveBeenCalledTimes(2);
  });

  it('reloads the timeline when the active session changes', async () => {
    eventsMock.mockResolvedValueOnce([realEvents[0]]);
    eventsMock.mockResolvedValueOnce(shellEvents);
    render(<ReplayPanel />);

    expect(
      await screen.findByRole('button', { name: /用户消息 · 请检查 README/ }),
    ).toBeInTheDocument();

    act(() => {
      useSessionStore.setState({ activeSessionId: 'session-2' });
    });

    expect(await screen.findByRole('button', { name: '工具开始 · shell' })).toBeInTheDocument();
    expect(eventsMock).toHaveBeenNthCalledWith(1, 'session-1');
    expect(eventsMock).toHaveBeenNthCalledWith(2, 'session-2');
  });

  it('groups events by user turns while keeping the global event cursor', async () => {
    eventsMock.mockResolvedValue([
      event({ seq: 1, type: 'RUN_FINISHED', aborted: false }),
      event({ seq: 2, type: 'USER_MESSAGE', role: 'USER', content: '第一轮问题' }),
      event({ seq: 3, type: 'ASSISTANT_MESSAGE', role: 'ASSISTANT', content: '第一轮回答' }),
      event({ seq: 4, type: 'USER_MESSAGE', role: 'USER', content: '第二轮问题' }),
      event({ seq: 5, type: 'ASSISTANT_MESSAGE', role: 'ASSISTANT', content: '第二轮回答' }),
    ]);
    render(<ReplayPanel />);

    expect(await screen.findByText('准备')).toBeInTheDocument();
    expect(screen.getByText('第 1 轮 · 第一轮问题')).toBeInTheDocument();
    expect(screen.getByText('第 2 轮 · 第二轮问题')).toBeInTheDocument();
    expect(screen.getByText('事件 1 / 5')).toBeInTheDocument();
  });

  it('expands available event details and collapses them on a second click', async () => {
    const user = userEvent.setup();
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    const toolStarted = await screen.findByRole('button', { name: '工具开始 · file' });
    expect(toolStarted).toHaveAttribute('aria-expanded', 'false');

    await user.click(toolStarted);

    expect(toolStarted).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('region', { name: '工具参数详情' })).toHaveTextContent(
      '{"path":"README.md","mode":"read"}',
    );

    await user.click(toolStarted);

    expect(toolStarted).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('region', { name: '工具参数详情' })).not.toBeInTheDocument();
  });

  it('keeps at most one event detail expanded', async () => {
    const user = userEvent.setup();
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    await user.click(await screen.findByRole('button', { name: '工具开始 · file' }));
    await user.click(screen.getByRole('button', { name: /工具完成 · file.*失败/ }));

    expect(screen.queryByRole('region', { name: '工具参数详情' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: '工具结果详情' }).textContent).toBe(
      '# Weaver\n\nJava Agent framework',
    );
  });

  it('scrolls the active event into view when stepping', async () => {
    const user = userEvent.setup();
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    });
    eventsMock.mockResolvedValue(realEvents);
    render(<ReplayPanel />);

    await user.click(await screen.findByRole('button', { name: '下一步' }));

    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
  });
});

describe('groupTurns', () => {
  it('starts a new group at every user message', () => {
    const events = [
      event({ seq: 1, type: 'USER_MESSAGE', role: 'USER', content: 'one' }),
      event({ seq: 2, type: 'ASSISTANT_MESSAGE', role: 'ASSISTANT', content: 'answer' }),
      event({ seq: 3, type: 'USER_MESSAGE', role: 'USER', content: 'two' }),
    ];

    expect(groupTurns(events)).toEqual([
      {
        turnNumber: 1,
        userMessage: events[0],
        events: [events[0], events[1]],
      },
      {
        turnNumber: 2,
        userMessage: events[2],
        events: [events[2]],
      },
    ]);
  });

  it('puts orphan events before the first user message in a prepare group', () => {
    const orphan = event({ seq: 1, type: 'RUN_FINISHED', aborted: true });
    const userMessage = event({
      seq: 2,
      type: 'USER_MESSAGE',
      role: 'USER',
      content: 'start',
    });

    expect(groupTurns([orphan, userMessage])).toEqual([
      {
        turnNumber: null,
        userMessage: null,
        events: [orphan],
      },
      {
        turnNumber: 1,
        userMessage,
        events: [userMessage],
      },
    ]);
  });
});
