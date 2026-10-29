package cn.yhjyhj.weaver.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionEventCodecTest {

    private final SessionEventCodec codec = new SessionEventCodec(); // 被测编解码器

    @Test
    void roundTripsEverySessionEventVariant() {
        List<SessionEvent> events = List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello"),
                new SessionEvent.ToolCallStarted(
                        "call-1", "shell", "{\"command\":\"git status\"}"),
                new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("clean", false, 12L)),
                new SessionEvent.ApprovalRequested(
                        "call-1", "shell", Reversibility.RECOVERABLE),
                new SessionEvent.ApprovalResolved("call-1", true, "user"),
                new SessionEvent.RunFinished(true));

        for (SessionEvent event : events) {
            assertEquals(event, codec.decode(codec.encode(event)));
        }
    }

    @Test
    void rejectsUnknownEventType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("{\"type\":\"UnknownEvent\"}"));
    }

    @Test
    void rejectsMissingRequiredField() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("{\"type\":\"MessageAdded\",\"role\":\"USER\"}"));
    }

    @Test
    void rejectsInvalidEnum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(
                        "{\"type\":\"MessageAdded\",\"role\":\"SYSTEM\",\"content\":\"hello\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(
                        "{\"type\":\"ApprovalRequested\",\"callId\":\"call-1\","
                                + "\"toolName\":\"shell\",\"reversibility\":\"UNKNOWN\"}"));
    }
}
