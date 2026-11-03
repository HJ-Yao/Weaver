import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useServerStatusStore } from '@/app/server-status-store';
import { useSessionStore } from '@/entities/session/store';
import type { ServerStatusDto } from '@/shared/api/status';
import { ChatPage } from './ChatPage';

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
    transcript: [],
    lastSeq: 0,
    running: false,
    status: 'ready',
    error: null,
  });
  useServerStatusStore.setState({ phase: 'connected', status: connectedStatus, error: null });
});

describe('ChatPage welcome', () => {
  it('renders the composer without chat chrome when no session is active', () => {
    render(<ChatPage />);

    expect(screen.getByRole('heading', { name: '想构建点什么？' })).toBeInTheDocument();
    expect(
      screen.getByText('描述一个任务，或直接提问；第一句话会自动创建会话。'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('消息输入框')).toBeInTheDocument();
    expect(screen.queryByRole('log', { name: '对话消息' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '回放' })).not.toBeInTheDocument();
  });
});
