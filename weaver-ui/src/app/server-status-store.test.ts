import { beforeEach, describe, expect, it } from 'vitest';
import type { ServerStatusDto } from '@/shared/api/status';
import { useServerStatusStore } from './server-status-store';

const status: ServerStatusDto = {
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
  useServerStatusStore.setState({ phase: 'connecting', status: null, error: null });
});

describe('server status store', () => {
  it('stores a connected status and clears a previous error', () => {
    useServerStatusStore.setState({ error: 'offline' });

    useServerStatusStore.getState().setConnected(status);

    expect(useServerStatusStore.getState()).toMatchObject({
      phase: 'connected',
      status,
      error: null,
    });
  });

  it('stores a disconnected error without exposing stale server details', () => {
    useServerStatusStore.setState({ phase: 'connected', status });

    useServerStatusStore.getState().setDisconnected('service unavailable');

    expect(useServerStatusStore.getState()).toMatchObject({
      phase: 'disconnected',
      status: null,
      error: 'service unavailable',
    });
  });

  it('returns to a clean connecting state', () => {
    useServerStatusStore.setState({
      phase: 'disconnected',
      status: null,
      error: 'service unavailable',
    });

    useServerStatusStore.getState().setConnecting();

    expect(useServerStatusStore.getState()).toMatchObject({
      phase: 'connecting',
      status: null,
      error: null,
    });
  });
});
