import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceDiffDto } from '@/shared/api/workspace';

const { diffMock } = vi.hoisted(() => ({
  diffMock: vi.fn(),
}));

vi.mock('@/shared/api/workspace', () => ({
  workspaceApi: {
    diff: diffMock,
  },
}));

async function loadPanel(mockMode: boolean) {
  vi.stubEnv('VITE_USE_MOCK', mockMode ? 'true' : 'false');
  vi.resetModules();

  const [testingLibrary, userEventModule, panelModule, uiStoreModule] = await Promise.all([
    import('@testing-library/react'),
    import('@testing-library/user-event'),
    import('./DiffPanel'),
    import('@/app/ui-store'),
  ]);

  uiStoreModule.useUiStore.setState({ openDiff: null });

  return {
    render: () => testingLibrary.render(<panelModule.DiffPanel />),
    screen: testingLibrary.screen,
    user: userEventModule.default.setup(),
    useUiStore: uiStoreModule.useUiStore,
  };
}

beforeEach(() => {
  diffMock.mockReset();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('DiffPanel', () => {
  it('renders demo files in mock mode and opens the existing viewer on click', async () => {
    const panel = await loadPanel(true);
    panel.render();

    const fileButton = panel.screen.getByRole('button', {
      name: /core\/approval\/RuleBasedApprovalPolicy\.java/,
    });
    await panel.user.click(fileButton);

    expect(diffMock).not.toHaveBeenCalled();
    expect(panel.useUiStore.getState().openDiff?.path).toBe(
      'core/approval/RuleBasedApprovalPolicy.java',
    );
  });

  it('shows a loading state while the workspace request is pending', async () => {
    diffMock.mockReturnValue(new Promise<WorkspaceDiffDto>(() => undefined));
    const panel = await loadPanel(false);
    panel.render();

    expect(panel.screen.getByText('加载中…')).toBeInTheDocument();
    expect(diffMock).toHaveBeenCalledTimes(1);
  });

  it('shows an error and retries the workspace request', async () => {
    diffMock
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ files: [], truncated: false });
    const panel = await loadPanel(false);
    panel.render();

    expect(await panel.screen.findByText(/工作区改动加载失败：offline/)).toBeInTheDocument();
    await panel.user.click(panel.screen.getByRole('button', { name: '重试' }));

    expect(await panel.screen.findByText('工作区没有未提交改动')).toBeInTheDocument();
    expect(diffMock).toHaveBeenCalledTimes(2);
  });

  it('shows the empty state when the workspace has no changes', async () => {
    diffMock.mockResolvedValue({ files: [], truncated: false });
    const panel = await loadPanel(false);
    panel.render();

    expect(await panel.screen.findByText('工作区没有未提交改动')).toBeInTheDocument();
  });
});
