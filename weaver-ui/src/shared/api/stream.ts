import { API_BASE, ApiError } from './http';

/**
 * Streaming contract for `POST /sessions/{id}/messages`.
 *
 * The endpoint returns `text/event-stream`, but it is a POST — `EventSource`
 * cannot send a body, so the response is consumed with `fetch` + a minimal SSE
 * parser. Event names and payloads mirror `SseStreamSink` / `SseApprovalGate`:
 *
 *   delta    -> plain text chunk
 *   tool     -> { callId, toolName, argumentsJson }
 *   approval -> { callId, toolName, argumentsJson, reversibility, timeoutSeconds }
 *   done     -> "STOP" | "LENGTH" | "ERROR"
 *   error    -> plain text
 */

/** A tool invocation announced by the model. */
export interface StreamToolCall {
  callId: string;
  toolName: string;
  argumentsJson: string;
}

/** A pending approval; answer it through `sessionApi.resolveApproval`. */
export interface StreamApproval {
  callId: string;
  toolName: string;
  argumentsJson: string;
  reversibility: 'REVERSIBLE' | 'IRREVERSIBLE';
  timeoutSeconds: number;
}

/** Reason the model stream ended. */
export type StopReason = 'STOP' | 'LENGTH' | 'ERROR';

/** Callbacks invoked while the stream is open. All are optional. */
export interface StreamHandlers {
  onDelta?: (delta: string) => void;
  onTool?: (call: StreamToolCall) => void;
  onApproval?: (approval: StreamApproval) => void;
  onDone?: (reason: StopReason) => void;
  onError?: (message: string) => void;
}

/**
 * Send a user message and consume the run's SSE stream.
 *
 * Resolves when the server closes the response. Aborting via `signal` is a
 * normal end (the user pressed Stop), so it does not call `onError`.
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

      // Spring writes bare `\n`; normalising keeps the frame delimiter simple.
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
      // Unknown events are ignored so the server can add new ones safely.
      break;
  }
}

/** Split one SSE frame into its `event:` name and joined `data:` payload. */
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
