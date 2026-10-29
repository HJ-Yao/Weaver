package cn.yhjyhj.weaver.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionModelHistoryReplayTest {

    @Test
    void replayModelHistoryKeepsAssistantAndToolPairingInEventOrder() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "read file")),
                new StoredEvent(2L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "checking")),
                new StoredEvent(3L, new SessionEvent.ToolCallStarted(
                        "call-1", "file", "{\"path\":\"a.txt\"}")),
                new StoredEvent(4L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 2L))),
                new StoredEvent(5L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "done")),
                new StoredEvent(6L, new SessionEvent.RunFinished(false)));

        List<ModelMessage> history = SessionEvents.replayModelHistory(events);

        assertEquals(List.of(
                new ModelMessage.User("read file"),
                new ModelMessage.Assistant(
                        "checking",
                        List.of(new StreamChunk.ToolCall(
                                "call-1", "file", "{\"path\":\"a.txt\"}"))),
                new ModelMessage.Tool("call-1", "ok", false),
                new ModelMessage.Assistant("done", List.of())),
                history);
    }

    @Test
    void replayModelHistorySynthesizesEmptyAssistantForToolOnlyTurn() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "read file")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-1", "file", "{\"path\":\"a.txt\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 2L))),
                new StoredEvent(4L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "done")));

        List<ModelMessage> history = SessionEvents.replayModelHistory(events);

        assertEquals(List.of(
                new ModelMessage.User("read file"),
                new ModelMessage.Assistant(
                        "",
                        List.of(new StreamChunk.ToolCall(
                                "call-1", "file", "{\"path\":\"a.txt\"}"))),
                new ModelMessage.Tool("call-1", "ok", false),
                new ModelMessage.Assistant("done", List.of())),
                history);
    }

    @Test
    void replayModelHistoryGroupsParallelCallsIntoOneAssistantMessage() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "inspect project")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-a", "file", "{\"path\":\"a.txt\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallStarted(
                        "call-b", "shell", "{\"command\":\"git status\"}")),
                new StoredEvent(4L, new SessionEvent.ToolCallCompleted(
                        "call-b", new ToolResult("clean", false, 3L))),
                new StoredEvent(5L, new SessionEvent.ToolCallCompleted(
                        "call-a", new ToolResult("content", false, 2L))),
                new StoredEvent(6L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "inspected")));

        List<ModelMessage> history = SessionEvents.replayModelHistory(events);

        assertEquals(List.of(
                new ModelMessage.User("inspect project"),
                new ModelMessage.Assistant(
                        "",
                        List.of(
                                new StreamChunk.ToolCall(
                                        "call-a", "file", "{\"path\":\"a.txt\"}"),
                                new StreamChunk.ToolCall(
                                        "call-b", "shell", "{\"command\":\"git status\"}"))),
                new ModelMessage.Tool("call-b", "clean", false),
                new ModelMessage.Tool("call-a", "content", false),
                new ModelMessage.Assistant("inspected", List.of())),
                history);
    }

    @Test
    void replayModelHistoryStartsNewAssistantAfterCompletedToolRound() {
        var events = List.of(
                new StoredEvent(1L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "inspect twice")),
                new StoredEvent(2L, new SessionEvent.ToolCallStarted(
                        "call-first", "file", "{\"path\":\"a.txt\"}")),
                new StoredEvent(3L, new SessionEvent.ToolCallCompleted(
                        "call-first", new ToolResult("first", false, 1L))),
                new StoredEvent(4L, new SessionEvent.ToolCallStarted(
                        "call-second", "shell", "{\"command\":\"pwd\"}")),
                new StoredEvent(5L, new SessionEvent.ToolCallCompleted(
                        "call-second", new ToolResult("second", true, 2L))),
                new StoredEvent(6L, new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "finished")));

        List<ModelMessage> history = SessionEvents.replayModelHistory(events);

        assertEquals(List.of(
                new ModelMessage.User("inspect twice"),
                new ModelMessage.Assistant(
                        "",
                        List.of(new StreamChunk.ToolCall(
                                "call-first", "file", "{\"path\":\"a.txt\"}"))),
                new ModelMessage.Tool("call-first", "first", false),
                new ModelMessage.Assistant(
                        "",
                        List.of(new StreamChunk.ToolCall(
                                "call-second", "shell", "{\"command\":\"pwd\"}"))),
                new ModelMessage.Tool("call-second", "second", true),
                new ModelMessage.Assistant("finished", List.of())),
                history);
    }
}
