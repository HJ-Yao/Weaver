import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { settingsApi, type ModelSettingsDto, type ModelViewDto } from '@/shared/api/settings';
import { ModelSettings } from './ModelSettings';

const { refreshMock } = vi.hoisted(() => ({
  refreshMock: vi.fn<() => Promise<void>>(),
}));

vi.mock('@/shared/api/settings', () => ({
  settingsApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    remove: vi.fn(),
  },
}));

vi.mock('@/app/server-status-store', () => ({
  useServerStatusStore: (
    selector: (state: { refresh: () => Promise<void> }) => unknown,
  ) => selector({ refresh: refreshMock }),
}));

const mockedSettingsApi = vi.mocked(settingsApi);

const activeModel: ModelViewDto = {
  id: 'model-active',
  name: 'DeepSeek Chat',
  protocol: 'openai-chat-completions',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
  hasKey: true,
  source: 'settings',
};

const inactiveModel: ModelViewDto = {
  id: 'model-inactive',
  name: 'Claude Sonnet',
  protocol: 'anthropic-messages',
  baseUrl: 'https://api.anthropic.com',
  model: 'claude-sonnet-4',
  hasKey: false,
  source: 'settings',
};

const envModel: ModelViewDto = {
  id: 'env',
  name: 'Environment model',
  protocol: 'openai-chat-completions',
  baseUrl: 'https://env.example.com',
  model: 'env-model',
  hasKey: false,
  source: 'env',
};

function settingsView(activeId: string | null, models: ModelViewDto[]): ModelSettingsDto {
  return { activeId, models };
}

function getModelRow(name: string): HTMLElement {
  const row = screen.getByText(name).closest('[role="listitem"]');
  if (!(row instanceof HTMLElement)) {
    throw new Error(`Model row not found: ${name}`);
  }
  return row;
}

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
  vi.clearAllMocks();
  refreshMock.mockResolvedValue(undefined);
});

describe('ModelSettings', () => {
  it('renders persisted models with current, environment, and missing-key badges', async () => {
    mockedSettingsApi.list.mockResolvedValue(
      settingsView(activeModel.id, [activeModel, inactiveModel, envModel]),
    );

    render(<ModelSettings />);

    expect(await screen.findByText('DeepSeek Chat')).toBeInTheDocument();
    expect(screen.getByText('openai-chat-completions · deepseek-chat')).toBeInTheDocument();
    expect(screen.getByText('当前')).toBeInTheDocument();
    expect(screen.getByText('环境')).toBeInTheDocument();
    expect(within(getModelRow('Claude Sonnet')).getByText('未配置密钥')).toBeInTheDocument();
  });

  it('activates another model, reloads the list, and refreshes server status', async () => {
    const user = userEvent.setup();
    const updatedModel = { ...inactiveModel, hasKey: true };
    mockedSettingsApi.list
      .mockResolvedValueOnce(settingsView(activeModel.id, [activeModel, inactiveModel, envModel]))
      .mockResolvedValueOnce(settingsView(inactiveModel.id, [activeModel, updatedModel, envModel]));
    mockedSettingsApi.activate.mockResolvedValue(undefined);

    render(<ModelSettings />);

    await screen.findByText(inactiveModel.name);
    await user.click(
      within(getModelRow(inactiveModel.name)).getByRole('button', { name: '启用' }),
    );

    await waitFor(() => {
      expect(mockedSettingsApi.activate).toHaveBeenCalledWith(inactiveModel.id);
      expect(mockedSettingsApi.list).toHaveBeenCalledTimes(2);
      expect(refreshMock).toHaveBeenCalledTimes(1);
    });
  });

  it('creates a model, activates it, and reloads the list', async () => {
    const user = userEvent.setup();
    const createdModel: ModelViewDto = {
      id: 'model-created',
      name: 'New model',
      protocol: 'anthropic-messages',
      baseUrl: 'https://new.example.com',
      model: 'new-model',
      hasKey: true,
      source: 'settings',
    };
    mockedSettingsApi.list.mockResolvedValue(settingsView(envModel.id, [envModel]));
    mockedSettingsApi.create.mockResolvedValue(createdModel);
    mockedSettingsApi.activate.mockResolvedValue(undefined);

    render(<ModelSettings />);

    await user.click(await screen.findByRole('button', { name: /添加配置/ }));
    const dialog = await screen.findByRole('dialog', { name: '添加模型配置' });
    await user.type(within(dialog).getByLabelText('名称'), createdModel.name);
    await user.selectOptions(
      within(dialog).getByLabelText('协议'),
      createdModel.protocol,
    );
    await user.type(within(dialog).getByLabelText('Base URL'), createdModel.baseUrl);
    await user.type(within(dialog).getByLabelText('模型'), createdModel.model);
    await user.type(within(dialog).getByLabelText('API Key'), 'secret-key');
    await user.click(within(dialog).getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(mockedSettingsApi.create).toHaveBeenCalledWith({
        name: createdModel.name,
        protocol: createdModel.protocol,
        baseUrl: createdModel.baseUrl,
        model: createdModel.model,
        apiKey: 'secret-key',
      });
      expect(mockedSettingsApi.activate).toHaveBeenCalledWith(createdModel.id);
      expect(mockedSettingsApi.list).toHaveBeenCalledTimes(2);
      expect(refreshMock).toHaveBeenCalledTimes(1);
    });
    expect(mockedSettingsApi.create.mock.invocationCallOrder[0]).toBeLessThan(
      mockedSettingsApi.activate.mock.invocationCallOrder[0],
    );
  });

  it('prefills edits and omits a blank API key from the update', async () => {
    const user = userEvent.setup();
    mockedSettingsApi.list.mockResolvedValue(
      settingsView(activeModel.id, [activeModel, inactiveModel, envModel]),
    );
    mockedSettingsApi.update.mockResolvedValue({ ...inactiveModel, name: 'Renamed model' });

    render(<ModelSettings />);

    const editButtons = await screen.findAllByRole('button', { name: '编辑' });
    await user.click(editButtons[1]);
    const dialog = await screen.findByRole('dialog', { name: '编辑模型配置' });
    const nameInput = within(dialog).getByLabelText('名称');
    expect(nameInput).toHaveValue(inactiveModel.name);
    expect(within(dialog).getByLabelText('协议')).toHaveValue(inactiveModel.protocol);
    expect(within(dialog).getByLabelText('Base URL')).toHaveValue(inactiveModel.baseUrl);
    expect(within(dialog).getByLabelText('模型')).toHaveValue(inactiveModel.model);
    expect(within(dialog).getByLabelText('API Key')).toHaveValue('');

    await user.clear(nameInput);
    await user.type(nameInput, 'Renamed model');
    await user.click(within(dialog).getByRole('button', { name: '保存' }));

    await waitFor(() => expect(mockedSettingsApi.update).toHaveBeenCalled());
    const [, body] = mockedSettingsApi.update.mock.calls[0];
    expect(body).not.toHaveProperty('apiKey');
  });

  it('requires a second click before deleting a model', async () => {
    const user = userEvent.setup();
    mockedSettingsApi.list.mockResolvedValue(
      settingsView(activeModel.id, [activeModel, inactiveModel, envModel]),
    );
    mockedSettingsApi.remove.mockResolvedValue(undefined);

    render(<ModelSettings />);

    await user.click(await screen.findByRole('button', { name: '删除' }));

    expect(mockedSettingsApi.remove).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '确认删除' }));

    await waitFor(() => {
      expect(mockedSettingsApi.remove).toHaveBeenCalledWith(inactiveModel.id);
      expect(mockedSettingsApi.list).toHaveBeenCalledTimes(2);
    });
  });

  it('does not expose edit or delete actions for the environment model', async () => {
    mockedSettingsApi.list.mockResolvedValue(settingsView(envModel.id, [envModel]));

    render(<ModelSettings />);

    await screen.findByText('Environment model');
    const envRow = getModelRow('Environment model');
    expect(within(envRow).queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(within(envRow).queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
  });
});
