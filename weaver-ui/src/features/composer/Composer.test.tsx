import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import type { ServerStatusDto } from '@/shared/api/status';
import { useServerStatusStore } from '@/app/server-status-store';
import { Composer } from './Composer';

const originalCancelRun = useSessionStore.getState().cancelRun; // 测试后恢复真实 action

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
  useSessionStore.setState({ running: false, cancelRun: originalCancelRun });
  useServerStatusStore.setState({ phase: 'connecting', status: null, error: null });
});

afterEach(() => {
  useSessionStore.setState({ running: false, cancelRun: originalCancelRun });
});

describe('Composer', () => {
  it('shows the configured model, exposes its protocol, and never renders invented models', () => {
    useServerStatusStore.setState({ phase: 'connected', status: connectedStatus, error: null });

    const { container } = render(<Composer />);

    expect(screen.getByText('deepseek-chat')).toHaveAttribute('title', 'openai-chat-completions');
    expect(container.textContent).not.toMatch(/Claude|GPT/i);
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
});
