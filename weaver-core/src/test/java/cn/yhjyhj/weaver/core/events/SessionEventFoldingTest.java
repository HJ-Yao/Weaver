package cn.yhjyhj.weaver.core.events;

import cn.yhjyhj.weaver.core.tool.Reversibility;
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
                new StoredEvent(1L, new SessionEvent.ToolCallStarted(
                        "call-1", "shell", "{\"command\":\"git status\"}")),
                new StoredEvent(2L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("nothing to commit", false, 12L))));
        var snapshot = SessionEvents.replay(events);
        assertEquals(1, snapshot.toolCalls().size());
        var call = snapshot.toolCalls().get(0);
        assertEquals("call-1", call.callId());
        assertEquals("shell", call.toolName());
        assertEquals("{\"command\":\"git status\"}", call.argumentsJson());
        assertEquals(new ToolResult("nothing to commit", false, 12L), call.result());
    }

    @Test
    void replayAddsCompletedToolResultAsToolMessage() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "read file")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-1", "file", "{\"path\":\"a.txt\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 2L))));

        var snapshot = SessionEvents.replay(events);

        assertEquals(List.of(
                new SessionSnapshot.Message(SessionEvent.Role.USER, "read file"),
                new SessionSnapshot.Message(SessionEvent.Role.TOOL, "ok")),
                snapshot.messages());
    }

    @Test
    void replayPairsOutOfOrderCompletionsByCallId() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.ToolCallStarted(
                        "call-a", "read", "{\"path\":\"a.txt\"}")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-b", "search", "{\"query\":\"weaver\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted(
                        "call-b", new ToolResult("search result", false, 30L))),
                new StoredEvent(4L, new SessionEvent.ToolCallCompleted(
                        "call-a", new ToolResult("read result", true, 7L))));

        var snapshot = SessionEvents.replay(events);

        assertEquals(List.of(
                new SessionSnapshot.ToolCallRecord(
                        "call-b",
                        "search",
                        "{\"query\":\"weaver\"}",
                        new ToolResult("search result", false, 30L)),
                new SessionSnapshot.ToolCallRecord(
                        "call-a",
                        "read",
                        "{\"path\":\"a.txt\"}",
                        new ToolResult("read result", true, 7L))),
                snapshot.toolCalls());
    }

    @Test
    void replayPreservesRawArgumentsJson() {
        String argumentsJson = "{\"text\":\"line one\\nline two\",\"flags\":[true,null]}";
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.ToolCallStarted(
                        "call-raw", "echo", argumentsJson)),
                new StoredEvent(2L, new SessionEvent.ToolCallCompleted(
                        "call-raw", new ToolResult(argumentsJson, false, 1L))));

        var call = SessionEvents.replay(events).toolCalls().get(0);

        assertEquals(argumentsJson, call.argumentsJson());
        assertEquals(argumentsJson, call.result().content());
    }

    @Test
    void replayUsesUnknownToolForOrphanCompletion() {
        var events = List.of(new StoredEvent(1L, new SessionEvent.ToolCallCompleted(
                "missing", new ToolResult("failed", true, 3L))));

        var snapshot = assertDoesNotThrow(() -> SessionEvents.replay(events));

        assertEquals(List.of(new SessionSnapshot.ToolCallRecord(
                "missing", "unknown", "", new ToolResult("failed", true, 3L))),
                snapshot.toolCalls());
    }

    @Test
    void replayDoesNotProjectUncompletedToolCall() {
        var events = List.of(new StoredEvent(1L, new SessionEvent.ToolCallStarted(
                "call-pending", "shell", "{\"command\":\"sleep 10\"}")));

        var snapshot = SessionEvents.replay(events);

        assertTrue(snapshot.toolCalls().isEmpty());
    }

    @Test
    void replayMarksAbortedRuns() {
        var events = List.of(new StoredEvent(1L, new SessionEvent.RunFinished(true)));
        assertTrue(SessionEvents.replay(events).aborted());
        assertFalse(SessionEvents.replay(List.of(new StoredEvent(1L, new SessionEvent.RunFinished(false)))).aborted());
    }

    @Test
    void replayPairsToolCallsAcrossApprovalAuditEvents() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.ToolCallStarted(
                        "call-1", "shell", "status")),
                new StoredEvent(2L, new SessionEvent.ApprovalRequested(
                        "call-1", "shell", Reversibility.RECOVERABLE)),
                new StoredEvent(3L, new SessionEvent.ApprovalResolved(
                        "call-1", true, "user")),
                new StoredEvent(4L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 3L))));

        var snapshot = SessionEvents.replay(events);

        assertEquals(List.of(new SessionSnapshot.ToolCallRecord(
                "call-1",
                "shell",
                "status",
                new ToolResult("ok", false, 3L))), snapshot.toolCalls());
        assertEquals(
                new SessionSnapshot.Message(SessionEvent.Role.TOOL, "ok"),
                snapshot.messages().getFirst());
    }

    @Test
    void replayIsIdempotentForSameEventStream() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hi")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-1", "shell", "{\"command\":\"ls\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 2L))),
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
