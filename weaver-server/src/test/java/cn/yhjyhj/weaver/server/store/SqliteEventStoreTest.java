package cn.yhjyhj.weaver.server.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqliteEventStoreTest {

    private Path tempDirectory; // 每个测试独占的 SQLite 目录

    @BeforeEach
    void createTestDirectory() {
        tempDirectory = Path.of(
                        "target", "sqlite-event-store-tests", UUID.randomUUID().toString())
                .toAbsolutePath();
    }

    @Test
    void appendsAndReadsEventsInSequenceOrder() {
        Path database = tempDirectory.resolve("round-trip.db");
        List<SessionEvent> events = List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello"),
                new SessionEvent.ToolCallStarted("call-1", "file", "{\"path\":\"a.txt\"}"),
                new SessionEvent.ToolCallCompleted(
                        "call-1", new ToolResult("ok", false, 3L)));

        try (SqliteEventStore store = new SqliteEventStore(database)) {
            for (SessionEvent event : events) {
                store.append("session-1", event);
            }

            assertEquals(
                    List.of(1L, 2L, 3L),
                    store.from("session-1", 0L).stream().map(StoredEvent::seq).toList());
            assertEquals(
                    events,
                    store.from("session-1", 0L).stream().map(StoredEvent::event).toList());
        }
    }

    @Test
    void filtersEventsStrictlyAfterGivenSequence() {
        Path database = tempDirectory.resolve("filter.db");

        try (SqliteEventStore store = new SqliteEventStore(database)) {
            store.append("session-1", new SessionEvent.RunFinished(false));
            store.append("session-1", new SessionEvent.RunFinished(true));
            store.append("session-1", new SessionEvent.ApprovalResolved("call-1", true, "user"));

            List<StoredEvent> tail = store.from("session-1", 1L);

            assertEquals(List.of(2L, 3L), tail.stream().map(StoredEvent::seq).toList());
        }
    }

    @Test
    void restoresEventsAndSequenceAfterReopeningDatabase() {
        Path database = tempDirectory.resolve("restart.db");
        SessionEvent first = new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first");
        SessionEvent second = new SessionEvent.RunFinished(false);

        try (SqliteEventStore store = new SqliteEventStore(database)) {
            store.append("session-1", first);
            store.append("session-1", second);
        }

        try (SqliteEventStore reopened = new SqliteEventStore(database)) {
            assertEquals(
                    List.of(first, second),
                    reopened.from("session-1", 0L).stream()
                            .map(StoredEvent::event)
                            .toList());
            assertEquals(2L, reopened.lastSeq("session-1"));
            assertEquals(3L, reopened.append("session-1", new SessionEvent.RunFinished(true)));
        }
    }

    @Test
    void keepsSessionSequencesAndEventsIsolated() {
        Path database = tempDirectory.resolve("isolation.db");

        try (SqliteEventStore store = new SqliteEventStore(database)) {
            assertEquals(
                    1L,
                    store.append(
                            "session-a",
                            new SessionEvent.MessageAdded(SessionEvent.Role.USER, "from-a")));
            assertEquals(
                    1L,
                    store.append(
                            "session-b",
                            new SessionEvent.MessageAdded(SessionEvent.Role.USER, "from-b")));
            assertEquals(
                    2L,
                    store.append(
                            "session-a",
                            new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "a-reply")));

            assertEquals(2L, store.lastSeq("session-a"));
            assertEquals(1L, store.lastSeq("session-b"));
            assertEquals(0L, store.lastSeq("missing"));
            assertEquals(
                    List.of("from-a", "a-reply"),
                    store.from("session-a", 0L).stream()
                            .map(StoredEvent::event)
                            .map(SessionEvent.MessageAdded.class::cast)
                            .map(SessionEvent.MessageAdded::content)
                            .toList());
            assertEquals(
                    List.of("from-b"),
                    store.from("session-b", 0L).stream()
                            .map(StoredEvent::event)
                            .map(SessionEvent.MessageAdded.class::cast)
                            .map(SessionEvent.MessageAdded::content)
                            .toList());
        }
    }

    @Test
    void concurrentAppendsKeepSequenceContinuous() throws Exception {
        Path database = tempDirectory.resolve("concurrent.db");

        try (SqliteEventStore store = new SqliteEventStore(database);
                var executor = Executors.newFixedThreadPool(4)) {
            var appends = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(worker -> executor.submit(() -> {
                        for (int index = 0; index < 50; index++) {
                            store.append("session-1", new SessionEvent.RunFinished(false));
                        }
                    }))
                    .toList();
            for (var append : appends) {
                append.get();
            }

            List<StoredEvent> stored = store.from("session-1", 0L);
            assertEquals(200, stored.size());
            for (int index = 0; index < stored.size(); index++) {
                assertEquals(index + 1L, stored.get(index).seq());
            }
            assertEquals(200L, store.lastSeq("session-1"));
        }
    }

    @Test
    void closeIsIdempotentAndRejectsLaterOperations() {
        SqliteEventStore store =
                new SqliteEventStore(tempDirectory.resolve("closed.db"));

        store.close();
        assertDoesNotThrow(store::close);

        assertThrows(
                IllegalStateException.class,
                () -> store.append("session-1", new SessionEvent.RunFinished(false)));
        assertThrows(IllegalStateException.class, () -> store.from("session-1", 0L));
        assertThrows(IllegalStateException.class, () -> store.lastSeq("session-1"));
    }
}
