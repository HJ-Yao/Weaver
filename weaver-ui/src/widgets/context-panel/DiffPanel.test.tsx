import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useUiStore } from '@/app/ui-store';
import type { WorkspaceDiffDto } from '@/shared/api/workspace';
import { DiffPanel } from './DiffPanel';

const { diffMock } = vi.hoisted(() => ({
  diffMock: vi.fn(),
}));

vi.mock('@/shared/api/workspace', () => ({
  workspaceApi: {
    diff: diffMock,
  },
}));

beforeEach(() => {
  diffMock.mockReset();
  useUiStore.setState({ openDiff: null });
});

describe('DiffPanel', () => {
  it('renders files returned by the workspace API and opens the viewer on click', async () => {
    const user = userEvent.setup();
    diffMock.mockResolvedValue({
      truncated: false,
      files: [
        {
          path: 'src/App.tsx',
          additions: 2,
          deletions: 1,
          lines: [{ kind: 'add', text: '+export const ready = true;' }],
        },
      ],
    });
    render(<DiffPanel />);

    const fileButton = await screen.findByRole('button', { name: /src\/App\.tsx/ });
    await user.click(fileButton);

    expect(diffMock).toHaveBeenCalledTimes(1);
    expect(useUiStore.getState().openDiff?.path).toBe('src/App.tsx');
  });

  it('shows a loading state while the workspace request is pending', async () => {
    diffMock.mockReturnValue(new Promise<WorkspaceDiffDto>(() => undefined));
    render(<DiffPanel />);

    expect(screen.getByText('加载中…')).toBeInTheDocument();
    expect(diffMock).toHaveBeenCalledTimes(1);
  });

  it('shows an error and retries the workspace request', async () => {
    const user = userEvent.setup();
    diffMock
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ files: [], truncated: false });
    render(<DiffPanel />);

    expect(await screen.findByText(/工作区改动加载失败：offline/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('工作区没有未提交改动')).toBeInTheDocument();
    expect(diffMock).toHaveBeenCalledTimes(2);
  });

  it('shows the empty state when the workspace has no changes', async () => {
    diffMock.mockResolvedValue({ files: [], truncated: false });
    render(<DiffPanel />);

    expect(await screen.findByText('工作区没有未提交改动')).toBeInTheDocument();
  });
});
