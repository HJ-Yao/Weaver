import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import type { SessionSummary } from '@/entities/session/model';
import { SessionList } from './SessionList';

const sessions: SessionSummary[] = [
  {
    id: 'session-1',
    title: 'First',
    createdAt: '2026-10-31T10:00:00Z',
  },
  {
    id: 'session-2',
    title: 'Second',
    createdAt: '2026-10-31T11:00:00Z',
  },
];

const loadSessions = vi.fn().mockResolvedValue(undefined);
const selectSession = vi.fn().mockResolvedValue(undefined);
const renameSession = vi.fn().mockResolvedValue(true);
const deleteSession = vi.fn().mockResolvedValue(true);

beforeEach(() => {
  vi.clearAllMocks();
  loadSessions.mockResolvedValue(undefined);
  selectSession.mockResolvedValue(undefined);
  renameSession.mockResolvedValue(true);
  deleteSession.mockResolvedValue(true);
  useSessionStore.setState({
    sessions,
    activeSessionId: 'session-1',
    transcript: [],
    pending: [],
    lastSeq: 0,
    running: false,
    status: 'ready',
    error: null,
    loadSessions,
    selectSession,
    renameSession,
    deleteSession,
  });
});

describe('SessionList', () => {
  it('renders sessions and switches to the clicked session', async () => {
    const user = userEvent.setup();
    render(<SessionList />);

    expect(screen.getByText('First')).toBeInTheDocument();
    expect(screen.getByText('Second')).toBeInTheDocument();

    await user.click(screen.getByText('Second').closest('button') as HTMLButtonElement);

    expect(selectSession).toHaveBeenCalledWith('session-2');
  });

  it('renames inline on Enter and cancels on Escape', async () => {
    const user = userEvent.setup();
    render(<SessionList />);

    await user.click(screen.getByRole('button', { name: '重命名 First' }));
    const editor = screen.getByRole('textbox', { name: '重命名会话 First' });
    await user.clear(editor);
    await user.type(editor, 'Renamed{Enter}');

    await vi.waitFor(() => expect(renameSession).toHaveBeenCalledWith('session-1', 'Renamed'));

    vi.clearAllMocks();
    await user.click(screen.getByRole('button', { name: '重命名 First' }));
    const cancelledEditor = screen.getByRole('textbox', { name: '重命名会话 First' });
    await user.clear(cancelledEditor);
    await user.type(cancelledEditor, 'Discarded{Escape}');

    expect(renameSession).not.toHaveBeenCalled();
    expect(screen.queryByRole('textbox', { name: '重命名会话 First' })).not.toBeInTheDocument();
  });

  it('requires a second click before deleting a session', async () => {
    const user = userEvent.setup();
    render(<SessionList />);

    await user.click(screen.getByRole('button', { name: '删除 First' }));

    expect(deleteSession).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '确认删除 First' }));

    expect(deleteSession).toHaveBeenCalledWith('session-1');
  });

  it('does not switch sessions when an inline action is clicked', async () => {
    const user = userEvent.setup();
    render(<SessionList />);

    await user.click(screen.getByRole('button', { name: '重命名 Second' }));
    await user.click(screen.getByRole('button', { name: '删除 First' }));

    expect(selectSession).not.toHaveBeenCalled();
  });
});
