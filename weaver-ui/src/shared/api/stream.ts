import { API_BASE, ApiError } from './http';

/**
 * `POST /sessions/{id}/messages` 的流式契约。
 *
 * 该接口返回 `text/event-stream`，但请求方法是 POST。`EventSource` 无法发送
 * 请求体，因此用 `fetch` 加一个最小 SSE 解析器消费响应。
 * 事件名和载荷与 `SseStreamSink` / `SseApprovalGate` 对应：
 *
 *   delta    -> 纯文本分片
 *   tool     -> { callId, toolName, argumentsJson }
 *   approval -> { callId, toolName, argumentsJson, reversibility, timeoutSeconds }
 *   done     -> "STOP" | "LENGTH" | "ERROR"
 *   error    -> 纯文本
 */

/** 模型发起的工具调用。 */
export interface StreamToolCall {
  callId: string;
  toolName: string;
  argumentsJson: string;
}

/** 待处理的审批；通过 `sessionApi.resolveApproval` 响应。 */
export interface StreamApproval {
  callId: string;
  toolName: string;
  argumentsJson: string;
  reversibility: 'REVERSIBLE' | 'IRREVERSIBLE';
  timeoutSeconds: number;
}

/** 模型流结束的原因。 */
export type StopReason = 'STOP' | 'LENGTH' | 'ERROR';

/** 流打开期间调用的回调，均为可选。 */
export interface StreamHandlers {
  onDelta?: (delta: string) => void;
  onTool?: (call: StreamToolCall) => void;
  onApproval?: (approval: StreamApproval) => void;
  onDone?: (reason: StopReason) => void;
  onError?: (message: string) => void;
}

/**
 * 发送用户消息并消费本次运行的 SSE 流。
 *
 * 服务端关闭响应后完成。通过 `signal` 中止属于正常结束（用户点击停止），
 * 因此不会调用 `onError`。
 */
export async function streamMessage(
  sessionId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${API_BASE}/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ content }),
    signal,
  });

  if (!response.ok) {
    throw new ApiError(response.status, response.statusText);
  }
  if (!response.body) {
    throw new ApiError(0, 'the server returned an empty stream');
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;

      // Spring 使用裸 `\n`；统一后可以简化帧分隔逻辑。
      buffer += value.replace(/\r\n/g, '\n');
      let boundary = buffer.indexOf('\n\n');
      while (boundary !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        dispatchFrame(frame, handlers);
        boundary = buffer.indexOf('\n\n');
      }
    }
  } catch (error) {
    if (signal?.aborted) return;
    throw error;
  } finally {
    reader.releaseLock();
  }
}

function dispatchFrame(frame: string, handlers: StreamHandlers): void {
  const parsed = parseFrame(frame);
  if (!parsed) return;

  switch (parsed.event) {
    case 'delta':
      handlers.onDelta?.(parsed.data);
      break;
    case 'tool':
      handlers.onTool?.(JSON.parse(parsed.data) as StreamToolCall);
      break;
    case 'approval':
      handlers.onApproval?.(JSON.parse(parsed.data) as StreamApproval);
      break;
    case 'done':
      handlers.onDone?.(JSON.parse(parsed.data) as StopReason);
      break;
    case 'error':
      handlers.onError?.(parsed.data);
      break;
    default:
      // 忽略未知事件，使服务端可以安全地增加新事件。
      break;
  }
}

/** 将单个 SSE 帧拆分为 `event:` 名称和合并后的 `data:` 载荷。 */
function parseFrame(frame: string): { event: string; data: string } | null {
  let event = 'message';
  const data: string[] = [];

  for (const line of frame.split('\n')) {
    if (!line || line.startsWith(':')) continue;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') event = value;
    if (field === 'data') data.push(value);
  }

  return data.length === 0 ? null : { event, data: data.join('\n') };
}
