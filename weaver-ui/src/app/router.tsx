import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { ChatPage } from '@/pages/chat/ChatPage';
import { PluginsPage } from '@/pages/plugins/PluginsPage';
import { SettingsPage } from '@/pages/settings/SettingsPage';
import { AppShell } from './AppShell';

/**
 * 路由表。
 *
 * 与浏览器路由分开导出，测试才能用 `createMemoryRouter` 挂载同一棵路由树
 * （见 `App.test.tsx`）。
 *
 * TODO(M4): 使用 Tauri 打包后切换为 hash 或 memory 路由；
 * 自定义协议在没有回退页时无法提供深链接。
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <ChatPage /> },
      { path: 'plugins', element: <PluginsPage /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
