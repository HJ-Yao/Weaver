import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import { MessageStream } from './MessageStream';

beforeEach(() => {
  useSessionStore.setState({
    transcript: [],
  });
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
