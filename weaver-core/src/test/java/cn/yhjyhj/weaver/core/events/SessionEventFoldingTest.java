package cn.yhjyhj.weaver.core.events;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 事件折叠（回放）纯函数的契约：状态由事件流重放得出。 */
class SessionEventFoldingTest {

    @Test
    void replayRebuildsMessagesInOrder() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "加个接口")),
                new StoredEvent(2L, new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "好的")));
        var snapshot = SessionEvents.replay(events);
        assertEquals(2, snapshot.messages().size());
        assertEquals("加个接口", snapshot.messages().get(0).content());
        assertEquals(SessionEvent.Role.ASSISTANT, snapshot.messages().get(1).role());
    }

    @Test
    void replayPairsToolCallStartWithCompletion() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.ToolCallStarted("shell", "git status")),
                new StoredEvent(2L, new SessionEvent.ToolCallCompleted("shell", "nothing to commit")));
        var snapshot = SessionEvents.replay(events);
        assertEquals(1, snapshot.toolCalls().size());
        var call = snapshot.toolCalls().get(0);
        assertEquals("shell", call.toolName());
        assertEquals("git status", call.argsSummary());
        assertEquals("nothing to commit", call.resultSummary());
    }

    @Test
    void replayMarksAbortedRuns() {
        var events = List.of(new StoredEvent(1L, new SessionEvent.RunFinished(true)));
        assertTrue(SessionEvents.replay(events).aborted());
        assertFalse(SessionEvents.replay(List.of(new StoredEvent(1L, new SessionEvent.RunFinished(false)))).aborted());
    }

    @Test
    void replayIsIdempotentForSameEventStream() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hi")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted("shell", "ls")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted("shell", "ok")),
                new StoredEvent(4L, new SessionEvent.RunFinished(false)));
        assertEquals(SessionEvents.replay(events), SessionEvents.replay(events));
    }

    @Test
    void replayLastSeqTracksHighestSequence() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "a")),
                new StoredEvent(3L, new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "b")));
        assertEquals(3L, SessionEvents.replay(events).lastSeq());
    }
}