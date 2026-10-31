/**
 * Model connection profiles.
 *
 * TODO(M2): move these into `~/.weaver/config.json` behind a settings API and
 * keep API keys out of the browser entirely (they belong to the server process).
 */

/** Wire protocols the server implements. */
export type ModelProtocol = 'openai-chat-completions' | 'anthropic-messages';

/** One saved connection profile. */
export interface ModelConfig {
  id: string;
  name: string;
  protocol: ModelProtocol;
  baseUrl: string;
  model: string;
  /** Exactly one profile is active at a time. */
  active: boolean;
}

/** Selectable protocols with their display label. */
export const PROTOCOLS: { value: ModelProtocol; label: string }[] = [
  { value: 'openai-chat-completions', label: 'openai-chat-completions' },
  { value: 'anthropic-messages', label: 'anthropic-messages' },
];

/** Demo profiles; replaced by the settings API in M2. */
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
