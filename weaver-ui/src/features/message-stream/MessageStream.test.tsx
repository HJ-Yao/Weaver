import { render, screen } from '@testing-library/react';
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
});
