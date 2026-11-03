/** 服务端实现的通信协议。 */
export type ModelProtocol = 'openai-chat-completions' | 'anthropic-messages';

/** 可选择协议及其显示名称。 */
export const PROTOCOLS: { value: ModelProtocol; label: string }[] = [
  { value: 'openai-chat-completions', label: 'openai-chat-completions' },
  { value: 'anthropic-messages', label: 'anthropic-messages' },
];
