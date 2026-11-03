import { request } from './http';

/** 设置页可见的脱敏模型配置。 */
export interface ModelViewDto {
  /** 配置 id；环境配置固定为 `env`。 */
  id: string;
  /** 展示名称。 */
  name: string;
  /** 与模型供应商通信的协议。 */
  protocol: 'openai-chat-completions' | 'anthropic-messages';
  /** 模型 API 基础地址。 */
  baseUrl: string;
  /** 供应商侧模型标识。 */
  model: string;
  /** 服务端是否保存了非空 API Key。 */
  hasKey: boolean;
  /** 配置来源；环境配置不可编辑或删除。 */
  source: 'settings' | 'env';
}

/** 当前激活项与全部可选模型配置。 */
export interface ModelSettingsDto {
  /** 当前激活项 id；环境配置对应 `env`。 */
  activeId: string | null;
  /** 持久化配置与环境配置合并后的列表。 */
  models: ModelViewDto[];
}

/** 新增或更新模型配置时提交的非密钥字段与可选密钥。 */
export interface SaveModelRequest {
  /** 展示名称。 */
  name: string;
  /** 模型协议标识。 */
  protocol: string;
  /** 模型 API 基础地址。 */
  baseUrl: string;
  /** 供应商侧模型标识。 */
  model: string;
  /** 编辑时留空表示保留原密钥。 */
  apiKey?: string;
}

export const settingsApi = {
  /** 获取脱敏模型配置列表。 */
  list: () => request<ModelSettingsDto>('/settings/models'),
  /** 新增模型配置。 */
  create: (body: SaveModelRequest) =>
    request<ModelViewDto>('/settings/models', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  /** 更新持久化模型配置。 */
  update: (id: string, body: SaveModelRequest) =>
    request<ModelViewDto>(`/settings/models/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  /** 启用持久化模型或回退到环境模型。 */
  activate: (id: string) =>
    request<void>(`/settings/models/${id}/activate`, { method: 'POST' }),
  /** 删除未激活的持久化模型配置。 */
  remove: (id: string) =>
    request<void>(`/settings/models/${id}`, { method: 'DELETE' }),
};
