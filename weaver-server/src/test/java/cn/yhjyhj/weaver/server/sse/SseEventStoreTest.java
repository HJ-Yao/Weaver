package cn.yhjyhj.weaver.server.sse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseEventStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 验证 SSE 载荷字段

    @Test
    void persistsEventAndReturnsDelegateSequence() {
        SessionEvent.MessageAdded event =
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "hello");
        FakeSessionEventStore delegate = new FakeSessionEventStore(41L, List.of(), 41L);
        SseEventStore store = new SseEventStore(delegate, (eventName, data) -> {});

        long seq = store.append("session-1", event);

        assertEquals(41L, seq);
        assertEquals(List.of(event), delegate.appendedEvents);
    }

    @Test
    void pushesToolResultWithCompletedResultFields() {
        List<SentEvent> events = new ArrayList<>();
        FakeSessionEventStore delegate = new FakeSessionEventStore(8L, List.of(), 8L);
        SseEventStore store =
                new SseEventStore(delegate, (eventName, data) -> events.add(new SentEvent(eventName, data)));
        SessionEvent.ToolCallCompleted completed = new SessionEvent.ToolCallCompleted(
                "call-1", new ToolResult("file contents", true, 1250L));

        store.append("session-1", completed);

        assertEquals(1, events.size());
        SentEvent event = events.getFirst();
        assertEquals("tool_result", event.name());
        Map<String, Object> payload = JSON.convertValue(event.data(), new TypeReference<>() {});
        assertEquals("call-1", payload.get("callId"));
        assertEquals("file contents", payload.get("content"));
        assertEquals(true, payload.get("isError"));
        assertEquals(1250L, ((Number) payload.get("durationMs")).longValue());
    }

    @Test
    void doesNotSendOtherEventTypes() {
        List<SentEvent> events = new ArrayList<>();
        SseEventStore store = new SseEventStore(
                new FakeSessionEventStore(3L, List.of(), 3L),
                (eventName, data) -> events.add(new SentEvent(eventName, data)));

        store.append(
                "session-1",
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello"));

        assertEquals(List.of(), events);
    }

    @Test
    void keepsPersistenceSuccessfulWhenToolResultCannotBeSent() {
        SessionEvent.ToolCallCompleted completed = new SessionEvent.ToolCallCompleted(
                "call-1", new ToolResult("done", false, 25L));
        FakeSessionEventStore delegate = new FakeSessionEventStore(5L, List.of(), 5L);
        SseEventStore store = new SseEventStore(delegate, (eventName, data) -> {
            throw new IOException("client disconnected");
        });

        long seq = assertDoesNotThrow(() -> store.append("session-1", completed));

        assertEquals(5L, seq);
        assertEquals(List.of(completed), delegate.appendedEvents);
    }

    @Test
    void delegatesReplayAndSequenceQueries() {
        List<StoredEvent> replay =
                List.of(new StoredEvent(7L, new SessionEvent.RunFinished(false)));
        SseEventStore store = new SseEventStore(
                new FakeSessionEventStore(0L, replay, 12L),
                (eventName, data) -> {});

        assertEquals(replay, store.from("session-1", 6L));
        assertEquals(12L, store.lastSeq("session-1"));
    }

    private static final class FakeSessionEventStore implements SessionEventStore {

        private final long appendSeq; // append 返回的固定序号
        private final List<StoredEvent> replay; // from 返回的固定事件
        private final long lastSeq; // lastSeq 返回的固定序号
        private final List<SessionEvent> appendedEvents = new ArrayList<>(); // 已追加事件

        private FakeSessionEventStore(
                long appendSeq, List<StoredEvent> replay, long lastSeq) {
            this.appendSeq = appendSeq;
            this.replay = replay;
            this.lastSeq = lastSeq;
        }

        @Override
        public long append(String sessionId, SessionEvent event) {
            appendedEvents.add(event);
            return appendSeq;
        }

        @Override
        public List<StoredEvent> from(String sessionId, long afterSeq) {
            return replay;
        }

        @Override
        public long lastSeq(String sessionId) {
            return lastSeq;
        }
    }

    private record SentEvent(String name, Object data) {}
}
