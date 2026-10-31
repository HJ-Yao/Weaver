import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSessionStore } from '@/entities/session/store';
import { routes } from './router';

/** 初始 store 快照；每个测试前恢复，确保用例彼此独立。 */
const initialState = useSessionStore.getState();

function renderApp(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
}

beforeEach(() => {
  useSessionStore.setState({
    transcript: initialState.transcript,
    activeSessionId: initialState.activeSessionId,
    running: false,
  });
});

describe('app shell', () => {
  it('renders the active session and its transcript', () => {
    renderApp();

    expect(screen.getByRole('heading', { name: '审批栈重构' })).toBeInTheDocument();
    expect(screen.getByText(/规则表不要再靠正则啃 JSON/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '首页' })).toBeInTheDocument();
  });

  it('appends the user message and the mock reply on Enter', async () => {
    const user = userEvent.setup();
    renderApp();

    const input = screen.getByLabelText('消息输入框');
    await user.type(input, 'hello');
    await user.keyboard('{Enter}');

    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.getByText(/收到。我会先读取相关文件/)).toBeInTheDocument();
    expect(input).toHaveValue('');
  });

  it('switches the plugin catalogue through the sidebar tab', async () => {
    const user = userEvent.setup();
    renderApp('/plugins');

    expect(screen.getByRole('heading', { name: '插件' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /技能/ }));

    expect(screen.getByRole('heading', { name: '技能' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '＋ 添加技能' })).toBeInTheDocument();
  });

  it('opens the settings page from the rail', async () => {
    const user = userEvent.setup();
    renderApp();

    await user.click(screen.getByRole('link', { name: '设置' }));

    expect(screen.getByRole('heading', { name: '设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '外观' })).toBeInTheDocument();
  });
});
