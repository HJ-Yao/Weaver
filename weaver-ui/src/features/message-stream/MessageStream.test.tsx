import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import { MessageStream } from './MessageStream';

beforeEach(() => {
  useSessionStore.setState({
    transcript: [],
    running: false,
    deleteTurn: vi.fn().mockResolvedValue(true),
  });
  useUiStore.setState({ toast: null, showToast: vi.fn() });
});

describe('MessageStream', () => {
  it('renders assistant messages as Markdown', () => {
    useSessionStore.setState({
      transcript: [
        {
          kind: 'message',
          id: 'assistant-1',
          role: 'assistant',
          content: '**bold**',
        },
      ],
    });

    render(<MessageStream />);

    expect(screen.getByText('bold').tagName).toBe('STRONG');
  });

  it('keeps user messages as literal text', () => {
    useSessionStore.setState({
      transcript: [
        {
          kind: 'message',
          id: 'user-1',
          role: 'user',
          content: '**bold**',
        },
      ],
    });

    const { container } = render(<MessageStream />);

    expect(screen.getByText('**bold**')).toBeInTheDocument();
    expect(container.querySelector('strong')).toBeNull();
  });

  it('exposes the message sequence through the container title', () => {
    useSessionStore.setState({
      transcript: [
        {
          kind: 'message',
          id: 'user-seq',
          role: 'user',
          content: 'locate me',
          seq: 4,
        },
      ],
    });

    render(<MessageStream />);

    expect(screen.getByTitle('seq 4')).toBeInTheDocument();
    expect(screen.queryByText('seq 4')).not.toBeInTheDocument();
  });

  it('requires a second click before deleting a user turn', async () => {
    const user = userEvent.setup();
    const deleteTurn = vi.fn().mockResolvedValue(true);
    useSessionStore.setState({
      running: false,
      deleteTurn,
      transcript: [
        {
          kind: 'message',
          id: 'user-1',
          role: 'user',
          content: 'delete this turn',
          seq: 4,
        },
      ],
    });
    render(<MessageStream />);

    await user.hover(screen.getByText('delete this turn'));
    await user.click(screen.getByRole('button', { name: '删除这一轮及之后' }));

    expect(deleteTurn).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '确认删除' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '确认删除' }));

    expect(deleteTurn).toHaveBeenCalledWith(4);
  });

  it('hides the delete action while a run is active', () => {
    useSessionStore.setState({
      running: true,
      transcript: [
        {
          kind: 'message',
          id: 'user-running',
          role: 'user',
          content: 'still running',
          seq: 4,
        },
      ],
    });

    render(<MessageStream />);

    expect(screen.queryByRole('button', { name: '删除这一轮及之后' })).not.toBeInTheDocument();
  });

  it('shows a toast when turn deletion fails', async () => {
    const user = userEvent.setup();
    const showToast = vi.fn();
    useUiStore.setState({ showToast });
    useSessionStore.setState({
      running: false,
      deleteTurn: vi.fn().mockResolvedValue(false),
      transcript: [
        {
          kind: 'message',
          id: 'user-failed-delete',
          role: 'user',
          content: 'keep this turn',
          seq: 4,
        },
      ],
    });
    render(<MessageStream />);

    await user.click(screen.getByRole('button', { name: '删除这一轮及之后' }));
    await user.click(screen.getByRole('button', { name: '确认删除' }));

    expect(showToast).toHaveBeenCalledWith('删除失败：请先停止当前运行');
  });

  it('removes an approval card after the user resolves it', async () => {
    const user = userEvent.setup();
    useSessionStore.setState({
      transcript: [
        {
          kind: 'approval',
          id: 'call-approval',
          toolName: 'git.commit',
          argumentsJson: '{"message":"test"}',
          reversibility: 'IRREVERSIBLE',
          timeoutSeconds: 120,
          status: 'pending',
        },
      ],
    });

    render(<MessageStream />);

    expect(screen.getByText('批准结果会写入会话事件日志')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '批准' }));

    expect(screen.queryByText('等待审批')).not.toBeInTheDocument();
  });
});
