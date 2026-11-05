import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceDirectoryDto } from '@/shared/api/workspace';
import { workspaceApi } from '@/shared/api/workspace';
import { WorkspacePicker } from './WorkspacePicker';

vi.mock('@/shared/api/workspace', () => ({
  workspaceApi: {
    browse: vi.fn(),
  },
}));

const mockedWorkspaceApi = vi.mocked(workspaceApi);

const homeDirectory: WorkspaceDirectoryDto = {
  path: 'C:\\Users\\alice',
  parent: 'C:\\Users',
  directories: [
    { name: 'projects', path: 'C:\\Users\\alice\\projects' },
    { name: 'notes', path: 'C:\\Users\\alice\\notes' },
  ],
  roots: ['C:\\', 'D:\\'],
};

const projectsDirectory: WorkspaceDirectoryDto = {
  path: 'C:\\Users\\alice\\projects',
  parent: 'C:\\Users\\alice',
  directories: [{ name: 'weaver', path: 'C:\\Users\\alice\\projects\\weaver' }],
  roots: ['C:\\', 'D:\\'],
};

beforeAll(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = true;
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = false;
    },
  });
});

beforeEach(() => {
  mockedWorkspaceApi.browse.mockReset();
  mockedWorkspaceApi.browse.mockResolvedValue(homeDirectory);
});

describe('WorkspacePicker', () => {
  it('loads the home directory when opened and renders its path and child directories', async () => {
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={vi.fn()} />);

    const dialog = await screen.findByRole('dialog', { name: '选择工作目录' });
    expect(await within(dialog).findByText(homeDirectory.path)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'projects' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'notes' })).toBeInTheDocument();
    expect(mockedWorkspaceApi.browse).toHaveBeenCalledWith();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
  });

  it('browses a child directory and then uses the returned path as the current location', async () => {
    const user = userEvent.setup();
    mockedWorkspaceApi.browse
      .mockResolvedValueOnce(homeDirectory)
      .mockResolvedValueOnce(projectsDirectory);
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={vi.fn()} />);

    await user.click(await screen.findByRole('button', { name: 'projects' }));

    expect(mockedWorkspaceApi.browse).toHaveBeenLastCalledWith(projectsDirectory.path);
    expect(await screen.findByText(projectsDirectory.path)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'weaver' })).toBeInTheDocument();
  });

  it('submits the current directory when choosing the folder', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<WorkspacePicker open onSelect={onSelect} onClose={vi.fn()} />);

    await screen.findByText(homeDirectory.path);
    await user.click(screen.getByRole('button', { name: '选择此文件夹' }));

    expect(onSelect).toHaveBeenCalledWith(homeDirectory.path);
  });

  it('switches to another filesystem root', async () => {
    const user = userEvent.setup();
    mockedWorkspaceApi.browse.mockResolvedValueOnce(homeDirectory).mockResolvedValueOnce({
      ...homeDirectory,
      path: 'D:\\',
      parent: null,
      directories: [],
    });
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={vi.fn()} />);

    await user.click(await screen.findByRole('button', { name: 'D:\\' }));

    expect(mockedWorkspaceApi.browse).toHaveBeenLastCalledWith('D:\\');
    expect(await screen.findByTitle('D:\\')).toBeInTheDocument();
  });

  it('disables the parent button at a filesystem root', async () => {
    mockedWorkspaceApi.browse.mockResolvedValue({
      ...homeDirectory,
      path: 'C:\\',
      parent: null,
    });
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={vi.fn()} />);

    await screen.findByRole('button', { name: 'D:\\' });
    expect(screen.getByRole('button', { name: '上级' })).toBeDisabled();
  });

  it('shows an error and stays on the previous directory when browsing fails', async () => {
    const user = userEvent.setup();
    mockedWorkspaceApi.browse
      .mockResolvedValueOnce(homeDirectory)
      .mockRejectedValueOnce(new Error('bad directory'));
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={vi.fn()} />);

    await user.click(await screen.findByRole('button', { name: 'projects' }));

    expect(await screen.findByText('无法读取该目录')).toBeInTheDocument();
    expect(screen.getByText(homeDirectory.path)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'projects' })).toBeInTheDocument();
  });

  it('closes with Escape', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<WorkspacePicker open onSelect={vi.fn()} onClose={onClose} />);

    await screen.findByText(homeDirectory.path);
    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
