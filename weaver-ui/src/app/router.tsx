import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { ChatPage } from '@/pages/chat/ChatPage';
import { PluginsPage } from '@/pages/plugins/PluginsPage';
import { SettingsPage } from '@/pages/settings/SettingsPage';
import { AppShell } from './AppShell';

/**
 * Route table.
 *
 * Exported separately from the browser router so tests can mount the same tree
 * with `createMemoryRouter` (see `App.test.tsx`).
 *
 * TODO(M4): switch to a hash/memory router when packaging with Tauri, where the
 * custom protocol cannot serve deep links without a fallback.
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
