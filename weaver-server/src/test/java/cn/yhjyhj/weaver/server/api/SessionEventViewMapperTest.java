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
    void mapsMessageRolesToMessageEventTypes() {
        List<StoredEvent> stored = List.of(
                stored(1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello")),
                stored(2L, new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "hi")),
                stored(3L, new SessionEvent.MessageAdded(SessionEvent.Role.TOOL, "result")));

        assertEquals(
                List.of(
                        new SessionEventView(
                                1L, "USER_MESSAGE", "USER", null, null, null, null),
                        new SessionEventView(
                                2L, "ASSISTANT_MESSAGE", "ASSISTANT", null, null, null, null),
                        new SessionEventView(
                                3L, "TOOL_MESSAGE", "TOOL", null, null, null, null)),
                SessionEventViewMapper.from(stored));
    }

    @Test
    void mapsToolCallStartedWithToolNameAndCallId() {
        StoredEvent stored = stored(
                4L,
                new SessionEvent.ToolCallStarted("call-1", "file", "{\"path\":\"README.md\"}"));

        assertEquals(
                List.of(new SessionEventView(
                        4L, "TOOL_STARTED", null, "file", "call-1", null, null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsToolCallCompletedWithoutToolNameOrResultContent() {
        StoredEvent stored = stored(
                5L,
                new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("large result", false, 42L)));

        SessionEventView view =
                SessionEventViewMapper.from(List.of(stored)).getFirst();

        assertEquals(new SessionEventView(
                5L, "TOOL_COMPLETED", null, null, "call-1", null, null), view);
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
                        6L, "APPROVAL_REQUESTED", null, "shell", "call-2", null, null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsApprovalResolvedWithDecision() {
        StoredEvent stored = stored(
                7L,
                new SessionEvent.ApprovalResolved("call-2", true, "user"));

        assertEquals(
                List.of(new SessionEventView(
                        7L, "APPROVAL_RESOLVED", null, null, "call-2", true, null)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    @Test
    void mapsRunFinishedWithAbortFlag() {
        StoredEvent stored = stored(8L, new SessionEvent.RunFinished(true));

        assertEquals(
                List.of(new SessionEventView(
                        8L, "RUN_FINISHED", null, null, null, null, true)),
                SessionEventViewMapper.from(List.of(stored)));
    }

    private static StoredEvent stored(long seq, SessionEvent event) {
        return new StoredEvent(seq, event);
    }
}
