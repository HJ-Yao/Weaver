import { describe, expect, it } from 'vitest';
import type { SessionSnapshotDto } from '@/shared/api/sessions';
import { transcriptFromSnapshot } from './model';

const snapshot: SessionSnapshotDto = {
  messages: [
    { role: 'USER', content: 'hello' },
    { role: 'ASSISTANT', content: 'hi' },
    { role: 'TOOL', content: 'raw tool output' },
  ],
  toolCalls: [
    {
      callId: 'call-1',
      toolName: 'file.read',
      argumentsJson: '{"path":"a.java"}',
      result: { content: 'ok', isError: false, durationMs: 12 },
    },
    {
      callId: 'call-2',
      toolName: 'shell',
      argumentsJson: '{"command":"false"}',
      result: { content: 'boom', isError: true, durationMs: 30 },
    },
  ],
  approvals: [{ callId: 'call-3', toolName: 'git.commit', approved: false, reason: 'user' }],
  aborted: false,
  lastSeq: 9,
};

describe('transcriptFromSnapshot', () => {
  it('keeps user and assistant messages and drops raw TOOL messages', () => {
    const items = transcriptFromSnapshot(snapshot);
    const messages = items.filter((item) => item.kind === 'message');

    expect(messages).toHaveLength(2);
    expect(messages.map((message) => message.role)).toEqual(['user', 'assistant']);
  });

  it('maps tool results onto the done/failed lifecycle', () => {
    const tools = transcriptFromSnapshot(snapshot).filter((item) => item.kind === 'tool');

    expect(tools.map((tool) => [tool.id, tool.status])).toEqual([
      ['call-1', 'done'],
      ['call-2', 'failed'],
    ]);
  });

  it('maps approval records onto the resolved statuses', () => {
    const approvals = transcriptFromSnapshot(snapshot).filter((item) => item.kind === 'approval');

    expect(approvals).toHaveLength(1);
    expect(approvals[0]).toMatchObject({ id: 'call-3', status: 'rejected' });
  });
});
