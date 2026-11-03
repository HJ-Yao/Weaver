package cn.yhjyhj.weaver.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.server.api.dto.SessionEventView;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionEventViewMapperTest {

    @Test
    void mapsMessageRolesToMessageEventTypesAndContent() {
        List<StoredEvent> stored = List.of(
                stored(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello")),
                stored(2L, new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "hi")),
                stored(3L, new SessionEvent.MessageAdded(SessionEvent.Role.TOOL, "result")));

        assertEquals(
                List.of(
                        new SessionEventView(
                                1L,
                                "USER_MESSAGE",
                                "USER",
                                null,
                                null,
                                null,
                                null,
                                "hello",
                                null,
                                null,
                                null,
                                null),
                        new SessionEventView(
                                2L,
                                "ASSISTANT_MESSAGE",
                                "ASSISTANT",
                                null,
                                null,
                                null,
                                null,
                                "hi",
                                null,
                                null,
                                null,
                                null),
                        new SessionEventView(
                                3L,
                                "TOOL_MESSAGE",
                                "TOOL",
                                null,
                                null,
                                null,
                                null,
                                "result",
                                null,
                                null,
                                null,
                                null)),
                SessionEventViewMapper.from(stored));
    }

    @Test
    void mapsToolCallStartedWithToolNameCallIdAndArguments() {
        StoredEvent stored = stored(
                4L,
                new SessionEvent.ToolCallStarted("call-1", "file", "{\"path\":\"README.md\"}"));

        assertEquals(
                List.of(new SessionEventView(
                        4L,
                        "TOOL_STARTED",
                        null,
                        "file",
                        "call-1",
                        null,
                        null,
                        null,
                        "{\"path\":\"README.md\"}",
                        null,
                        null,
                        null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsToolCallCompletedWithResultContentAndErrorFlag() {
        StoredEvent stored = stored(
                5L,
                new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("large result", true, 42L)));

        SessionEventView view =
                SessionEventViewMapper.from(List.of(stored)).getFirst();

        assertEquals(new SessionEventView(
                5L,
                "TOOL_COMPLETED",
                null,
                null,
                "call-1",
                null,
                null,
                null,
                null,
                "large result",
                true,
                null), view);
        assertNull(view.toolName());
    }

    @Test
    void mapsApprovalRequestedWithToolNameAndCallId() {
        StoredEvent stored = stored(
                6L,
                new SessionEvent.ApprovalRequested(
                        "call-2", "shell", Reversibility.RECOVERABLE));

        assertEquals(
                List.of(new SessionEventView(
                        6L,
                        "APPROVAL_REQUESTED",
                        null,
                        "shell",
                        "call-2",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsApprovalResolvedWithDecisionAndReason() {
        StoredEvent stored = stored(
                7L,
                new SessionEvent.ApprovalResolved("call-2", true, "approved by user"));

        assertEquals(
                List.of(new SessionEventView(
                        7L,
                        "APPROVAL_RESOLVED",
                        null,
                        null,
                        "call-2",
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "approved by user")),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsRunFinishedWithAbortFlag() {
        StoredEvent stored = stored(8L, new SessionEvent.RunFinished(true));

        assertEquals(
                List.of(new SessionEventView(
                        8L,
                        "RUN_FINISHED",
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void truncatesContentFieldsAfterTwoThousandCodePoints() {
        String oversizedEmojiBody = "😀".repeat(2001);
        List<StoredEvent> stored = List.of(
                stored(9L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, oversizedEmojiBody)),
                stored(
                        10L,
                        new SessionEvent.ToolCallStarted("call-3", "file", oversizedEmojiBody)),
                stored(
                        11L,
                        new SessionEvent.ToolCallCompleted(
                                "call-3", new ToolResult(oversizedEmojiBody, false, 1L))),
                stored(
                        12L,
                        new SessionEvent.ApprovalResolved("call-3", false, oversizedEmojiBody)));

        List<SessionEventView> views = SessionEventViewMapper.from(stored);
        String expected = "😀".repeat(2000) + "…";

        assertEquals(expected, views.get(0).content());
        assertEquals(expected, views.get(1).argumentsJson());
        assertEquals(expected, views.get(2).resultContent());
        assertEquals(expected, views.get(3).reason());
    }

    @Test
    void keepsBodyAtLimitWithoutAppendingEllipsis() {
        String body = "a".repeat(2000);
        StoredEvent stored =
                stored(13L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, body));

        SessionEventView view =
                SessionEventViewMapper.from(List.of(stored)).getFirst();

        assertEquals(body, view.content());
    }

    private static StoredEvent stored(long seq, SessionEvent event) {
        return new StoredEvent(seq, event);
    }
}
