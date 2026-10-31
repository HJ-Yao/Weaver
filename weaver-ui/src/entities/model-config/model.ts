/**
 * 模型连接配置。
 *
 * TODO(M2): 将这些配置移到 `~/.weaver/config.json` 并置于设置 API 之后，
 * 同时确保 API Key 完全不进入浏览器（它们属于服务端进程）。
 */

/** 服务端实现的通信协议。 */
export type ModelProtocol = 'openai-chat-completions' | 'anthropic-messages';

/** 一条已保存的连接配置。 */
export interface ModelConfig {
  id: string;
  name: string;
  protocol: ModelProtocol;
  baseUrl: string;
  model: string;
  /** 同一时间只有一个配置处于启用状态。 */
  active: boolean;
}

/** 可选择协议及其显示名称。 */
export const PROTOCOLS: { value: ModelProtocol; label: string }[] = [
  { value: 'openai-chat-completions', label: 'openai-chat-completions' },
  { value: 'anthropic-messages', label: 'anthropic-messages' },
];

/** 演示配置；M2 将由设置 API 替换。 */
export const DEMO_MODEL_CONFIGS: ModelConfig[] = [
  {
    id: 'deepseek-chat',
    name: 'DeepSeek Chat',
    protocol: 'openai-chat-completions',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    active: true,
  },
  {
    id: 'claude-sonnet',
    name: 'Claude Sonnet',
    protocol: 'anthropic-messages',
    baseUrl: 'https://api.anthropic.com',
    model: 'claude-sonnet-4',
    active: false,
  },
];
