import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import type { ServerStatusDto } from '@/shared/api/status';
import { useServerStatusStore } from '@/app/server-status-store';
import { workspaceApi } from '@/shared/api/workspace';
import { Composer } from './Composer';

vi.mock('@/shared/api/workspace', () => ({
  workspaceApi: {
    browse: vi.fn(),
  },
}));

const mockedWorkspaceApi = vi.mocked(workspaceApi);
const originalSessionState = useSessionStore.getState(); // 测试后恢复真实 action
const originalUiState = useUiStore.getState();

const connectedStatus: ServerStatusDto = {
  status: 'ok',
  storage: 'sqlite',
  wal: true,
  workspace: 'F:\\workspace',
  maxSteps: 50,
  uptimeSeconds: 12,
  model: 'deepseek-chat',
  protocol: 'openai-chat-completions',
};

beforeEach(() => {
  useSessionStore.setState({
    sessions: [],
    activeSessionId: null,
    running: false,
    pending: [],
    sendMessage: originalSessionState.sendMessage,
    setWorkspace: originalSessionState.setWorkspace,
    cancelRun: originalSessionState.cancelRun,
    enqueueMessage: originalSessionState.enqueueMessage,
    removePending: originalSessionState.removePending,
    editPending: originalSessionState.editPending,
  });
  useServerStatusStore.setState({ phase: 'connecting', status: null, error: null });
  useUiStore.setState({ showToast: originalUiState.showToast, toast: null });
  mockedWorkspaceApi.browse.mockReset();
  mockedWorkspaceApi.browse
    .mockResolvedValueOnce({
      path: 'F:\\workspace',
      parent: 'F:\\',
      directories: [{ name: 'selected', path: 'F:\\workspace\\selected' }],
      roots: ['F:\\'],
    })
    .mockResolvedValue({
      path: 'F:\\workspace\\selected',
      parent: 'F:\\workspace',
      directories: [],
      roots: ['F:\\'],
    });
});

afterEach(() => {
  useSessionStore.setState({
    sessions: [],
    activeSessionId: null,
    running: false,
    pending: [],
    sendMessage: originalSessionState.sendMessage,
    setWorkspace: originalSessionState.setWorkspace,
    cancelRun: originalSessionState.cancelRun,
    enqueueMessage: originalSessionState.enqueueMessage,
    removePending: originalSessionState.removePending,
    editPending: originalSessionState.editPending,
  });
  useUiStore.setState({ showToast: originalUiState.showToast, toast: null });
});

describe('Composer', () => {
  it('shows the configured model, exposes its protocol, and never renders invented models', () => {
    useServerStatusStore.setState({ phase: 'connected', status: connectedStatus, error: null });

    const { container } = render(<Composer />);

    expect(screen.getByText('deepseek-chat')).toHaveAttribute('title', 'openai-chat-completions');
    expect(container.textContent).not.toMatch(/Claude|GPT/i);
  });

  it('opens the workspace picker and saves the selected directory when idle', async () => {
    const user = userEvent.setup();
    const setWorkspace = vi.fn().mockResolvedValue(true);
    useSessionStore.setState({
      sessions: [
        {
          id: 'session-1',
          title: 'Session',
          createdAt: '2026-11-05T10:00:00Z',
          workspace: 'F:\\workspace',
        },
      ],
      activeSessionId: 'session-1',
      setWorkspace,
    });
    render(<Composer />);

    await user.click(screen.getByRole('button', { name: '选择工作目录' }));
    const dialog = await screen.findByRole('dialog', { name: '选择工作目录' });
    await user.click(await within(dialog).findByRole('button', { name: 'selected' }));
    await within(dialog).findByText('F:\\workspace\\selected');
    await user.click(within(dialog).getByRole('button', { name: '选择此文件夹' }));

    expect(setWorkspace).toHaveBeenCalledWith('F:\\workspace\\selected');
  });

  it('disables workspace changes while a run is active', () => {
    useSessionStore.setState({ running: true });
    render(<Composer />);

    const button = screen.getByRole('button', { name: '选择工作目录' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', '运行中不能切换工作目录');
  });

  it('shows a toast when saving the selected workspace fails', async () => {
    const user = userEvent.setup();
    const setWorkspace = vi.fn().mockResolvedValue(false);
    const showToast = vi.fn();
    useSessionStore.setState({
      sessions: [
        {
          id: 'session-1',
          title: 'Session',
          createdAt: '2026-11-05T10:00:00Z',
          workspace: 'F:\\workspace',
        },
      ],
      activeSessionId: 'session-1',
      setWorkspace,
    });
    useUiStore.setState({ showToast });
    render(<Composer />);

    await user.click(screen.getByRole('button', { name: '选择工作目录' }));
    const dialog = await screen.findByRole('dialog', { name: '选择工作目录' });
    await user.click(await within(dialog).findByRole('button', { name: 'selected' }));
    await within(dialog).findByText('F:\\workspace\\selected');
    await user.click(within(dialog).getByRole('button', { name: '选择此文件夹' }));

    expect(showToast).toHaveBeenCalledWith('切换工作目录失败');
  });

  it('does not render the previously hard-coded workspaces', () => {
    const { container } = render(<Composer />);

    expect(container).not.toHaveTextContent('Weaver');
    expect(container).not.toHaveTextContent('ai-agent-scaffold');
    expect(container).not.toHaveTextContent('deepseek-harness-java');
  });

  it('shows an unavailable model marker while no status is connected', () => {
    render(<Composer />);

    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('shows stop while running and requests cancellation on click', async () => {
    const user = userEvent.setup();
    const cancelRun = vi.fn();
    useSessionStore.setState({ running: true, cancelRun });
    render(<Composer />);

    const stop = screen.getByRole('button', { name: '停止' });
    expect(stop).toBeEnabled();

    await user.click(stop);

    expect(cancelRun).toHaveBeenCalledTimes(1);
  });

  it('sends immediately on Enter while idle', async () => {
    const user = userEvent.setup();
    const sendMessage = vi.fn();
    const enqueueMessage = vi.fn();
    useSessionStore.setState({ sendMessage, enqueueMessage });
    render(<Composer />);

    const input = screen.getByRole('textbox', { name: '消息输入框' });
    await user.type(input, '空闲消息{Enter}');

    expect(sendMessage).toHaveBeenCalledWith('空闲消息');
    expect(enqueueMessage).not.toHaveBeenCalled();
    expect(input).toHaveValue('');
  });

  it('queues a TURN on Enter while running and clears the draft after success', async () => {
    const user = userEvent.setup();
    const sendMessage = vi.fn();
    const enqueueMessage = vi.fn().mockResolvedValue(true);
    useSessionStore.setState({ running: true, sendMessage, enqueueMessage });
    render(<Composer />);

    const input = screen.getByRole('textbox', { name: '消息输入框' });
    await user.type(input, '跑完顺手更新 README{Enter}');

    expect(enqueueMessage).toHaveBeenCalledWith('跑完顺手更新 README', 'TURN');
    expect(sendMessage).not.toHaveBeenCalled();
    expect(input).toHaveValue('');
  });

  it('queues a STEP on Ctrl+Enter while running', async () => {
    const user = userEvent.setup();
    const enqueueMessage = vi.fn().mockResolvedValue(true);
    useSessionStore.setState({ running: true, enqueueMessage });
    render(<Composer />);

    const input = screen.getByRole('textbox', { name: '消息输入框' });
    await user.type(input, '只改 main');
    await user.keyboard('{Control>}{Enter}{/Control}');

    expect(enqueueMessage).toHaveBeenCalledWith('只改 main', 'STEP');
    expect(input).toHaveValue('');
  });

  it('still sends on Ctrl+Enter while idle', async () => {
    const user = userEvent.setup();
    const sendMessage = vi.fn();
    const enqueueMessage = vi.fn();
    useSessionStore.setState({ running: false, sendMessage, enqueueMessage });
    render(<Composer />);

    const input = screen.getByRole('textbox', { name: '消息输入框' });
    await user.type(input, '空闲插话键{Control>}{Enter}{/Control}');

    expect(sendMessage).toHaveBeenCalledWith('空闲插话键');
    expect(enqueueMessage).not.toHaveBeenCalled();
  });

  it('shows a toast and keeps the draft when inbox delivery fails', async () => {
    const user = userEvent.setup();
    const enqueueMessage = vi.fn().mockResolvedValue(false);
    const showToast = vi.fn();
    useSessionStore.setState({ running: true, enqueueMessage });
    useUiStore.setState({ showToast });
    render(<Composer />);

    const input = screen.getByRole('textbox', { name: '消息输入框' });
    await user.type(input, '不要丢{Enter}');

    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('投递失败'));
    expect(input).toHaveValue('不要丢');
  });

  it('shows running controls only while a run is active', () => {
    const { rerender } = render(<Composer />);

    expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '插话' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '排队' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '停止' })).not.toBeInTheDocument();

    useSessionStore.setState({ running: true });
    rerender(<Composer />);

    expect(screen.queryByRole('button', { name: '发送' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '插话' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '排队' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '停止' })).toBeInTheDocument();
  });

  it('renders pending messages and delegates removal and inline editing', async () => {
    const user = userEvent.setup();
    const removePending = vi.fn();
    const editPending = vi.fn();
    useSessionStore.setState({
      running: true,
      pending: [
        { id: 'in-step', target: 'STEP', content: '只改 main，别动 test' },
        { id: 'in-turn', target: 'TURN', content: '跑完顺手更新 README' },
      ],
      removePending,
      editPending,
    });
    render(<Composer />);

    const queue = screen.getByRole('region', { name: '待发送' });
    expect(within(queue).getByText('插话')).toBeInTheDocument();
    expect(within(queue).getByText('排队')).toBeInTheDocument();
    expect(within(queue).getByText('只改 main，别动 test')).toBeInTheDocument();
    expect(within(queue).getByText('跑完顺手更新 README')).toBeInTheDocument();

    const turnEntry = within(queue).getByText('跑完顺手更新 README').closest('li');
    expect(turnEntry).not.toBeNull();
    await user.click(within(turnEntry as HTMLElement).getByRole('button', { name: '撤回' }));
    expect(removePending).toHaveBeenCalledWith('in-turn');

    const stepEntry = within(queue).getByText('只改 main，别动 test').closest('li');
    expect(stepEntry).not.toBeNull();
    await user.click(within(stepEntry as HTMLElement).getByRole('button', { name: '编辑' }));
    const editor = within(stepEntry as HTMLElement).getByRole('textbox', {
      name: '编辑待发送消息',
    });
    await user.clear(editor);
    await user.type(editor, '只改 main{Enter}');

    expect(editPending).toHaveBeenCalledWith('in-step', '只改 main');
    expect(
      within(stepEntry as HTMLElement).queryByRole('textbox', { name: '编辑待发送消息' }),
    ).not.toBeInTheDocument();
  });
});
