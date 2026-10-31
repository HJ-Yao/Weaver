import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import { Composer } from './Composer';

const originalCancelRun = useSessionStore.getState().cancelRun; // 测试后恢复真实 action

beforeEach(() => {
  useSessionStore.setState({ running: false, cancelRun: originalCancelRun });
});

afterEach(() => {
  useSessionStore.setState({ running: false, cancelRun: originalCancelRun });
});

describe('Composer', () => {
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
