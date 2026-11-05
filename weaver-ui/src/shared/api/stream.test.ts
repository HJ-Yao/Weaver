import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamMessage } from './stream';

/** 构造一个只发送一次数据的 SSE 响应。 */
function sseResponse(payload: string): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(payload));
      controller.close();
    },
  });

  return { ok: true, status: 200, body } as unknown as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('streamMessage', () => {
  it('keeps the leading space of a delta and reports the stop reason', async () => {
    const frames = 'event:delta\ndata:{"text":" 线程模型"}\n\nevent:done\ndata:"STOP"\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)));

    const deltas: string[] = [];
    const reasons: string[] = [];

    await streamMessage('session-1', 'hi', {
      onDelta: (delta) => deltas.push(delta),
      onDone: (reason) => reasons.push(reason),
    });

    expect(deltas).toEqual([' 线程模型']);
    expect(reasons).toEqual(['STOP']);
  });

  it('decodes tool result payloads', async () => {
    const frames =
      'event:tool_result\ndata:{"callId":"call-1","content":"ok","isError":false,"durationMs":12}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)));

    const results: unknown[] = [];

    await streamMessage('session-1', 'hi', {
      onToolResult: (result) => results.push(result),
    });

    expect(results).toEqual([{ callId: 'call-1', content: 'ok', isError: false, durationMs: 12 }]);
  });

  it('decodes inbox snapshots as an entry array', async () => {
    const expected = [
      { id: 'in-1', target: 'STEP', content: '只改 main' },
      { id: 'in-2', target: 'TURN', content: '更新 README' },
    ];
    const frames = `event:inbox\ndata:${JSON.stringify(expected)}\n\n`;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)));

    const snapshots: unknown[] = [];

    await streamMessage('session-1', 'hi', {
      onInbox: (entries) => snapshots.push(entries),
    });

    expect(snapshots).toEqual([expected]);
  });

  it('decodes user messages without trimming their text', async () => {
    const frames = 'event:user_message\ndata:{"text":"  保留前导空格"}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)));

    const messages: string[] = [];

    await streamMessage('session-1', 'hi', {
      onUserMessage: (text) => messages.push(text),
    });

    expect(messages).toEqual(['  保留前导空格']);
  });

  it('decodes generated session titles', async () => {
    const frames = 'event:session_title\ndata:{"title":"修复登录页"}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)));

    const titles: string[] = [];

    await streamMessage('session-1', 'hi', {
      onSessionTitle: (title) => titles.push(title),
    });

    expect(titles).toEqual(['修复登录页']);
  });
});
