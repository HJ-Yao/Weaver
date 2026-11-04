package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.store.SessionStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private InMemorySessionEventStore store; // 被测服务使用的事实源
    private InMemorySessionStore sessions; // 被测服务使用的元数据存储替身
    private SessionService service; // 会话元数据与快照服务

    @BeforeEach
    void setUp() {
        store = new InMemorySessionEventStore();
        sessions = new InMemorySessionStore();
        service = new SessionService(sessions, store);
    }

    @Test
    void createsFindableSessionWithGeneratedId() {
        SessionSummary created = service.create("First session");

        UUID.fromString(created.id());
        assertEquals("First session", created.title());
        assertEquals(created, service.find(created.id()).orElseThrow());
    }

    @Test
    void createsSessionWithDatabasePrecisionTimestamp() {
        SessionSummary created = service.create("Precise");

        assertEquals(0, created.createdAt().getNano() % 1_000_000);
    }

    @Test
    void listsSessionsByUpdatedTimeDescending() {
        SessionSummary first = service.create("First");
        SessionSummary second = service.create("Second");
        SessionSummary third = service.create("Third");

        List<SessionSummary> listed = service.list();
        List<Instant> updateTimes =
                listed.stream().map(summary -> sessions.updatedAt(summary.id())).toList();

        assertEquals(
                updateTimes.stream()
                        .sorted(Comparator.reverseOrder())
                        .toList(),
                updateTimes);
        assertEquals(
                Set.of(first.id(), second.id(), third.id()),
                listed.stream().map(SessionSummary::id).collect(Collectors.toSet()));
    }

    @Test
    void returnsEmptyWhenSessionDoesNotExist() {
        assertTrue(service.find("missing").isEmpty());
    }

    @Test
    void deletesExistingSessionAndReportsWhetherAnythingWasDeleted() {
        SessionSummary created = service.create("Delete me");

        assertTrue(service.delete(created.id()));
        assertFalse(service.delete(created.id()));
        assertTrue(service.find(created.id()).isEmpty());
    }

    @Test
    void deleteRemovesSessionEventsAsWellAsMetadata() {
        SessionSummary created = service.create("Delete events");
        store.append(
                created.id(),
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello"));

        assertTrue(service.delete(created.id()));

        assertEquals(List.of(), service.events(created.id()));
        assertEquals(0L, store.lastSeq(created.id()));
    }

    @Test
    void mapsUnnamedSessionTitleToNewSession() {
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");
        sessions.create("unnamed", null, createdAt);

        assertEquals("新会话", service.find("unnamed").orElseThrow().title());
        assertEquals("新会话", service.list().getFirst().title());
    }

    @Test
    void rejectsBlankManualRename() {
        SessionSummary created = service.create("Keep me");

        assertThrows(IllegalArgumentException.class, () -> service.rename(created.id(), " "));
        assertEquals("Keep me", service.find(created.id()).orElseThrow().title());
    }

    @Test
    void rejectsNullSessionTitleOnCreate() {
        assertThrows(IllegalArgumentException.class, () -> service.create(null));
    }

    @Test
    void rejectsNullManualRenameTitle() {
        SessionSummary created = service.create("Keep me");

        assertThrows(IllegalArgumentException.class, () -> service.rename(created.id(), null));
    }

    @Test
    void returnsBackfilledSessionCount() {
        sessions.backfillCount = 2;

        assertEquals(2, service.backfill());
    }

    @Test
    void replaysStoredEventsIntoSessionSnapshot() {
        SessionSummary created = service.create("Replay me");
        store.append(
                created.id(),
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello"));
        store.append(
                created.id(),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "echo: hello"));

        var snapshot = service.snapshot(created.id());

        assertEquals(2L, snapshot.lastSeq());
        assertEquals(
                List.of(
                        new cn.yhjyhj.weaver.core.events.SessionSnapshot.Message(
                                SessionEvent.Role.USER, "hello"),
                        new cn.yhjyhj.weaver.core.events.SessionSnapshot.Message(
                                SessionEvent.Role.ASSISTANT, "echo: hello")),
                snapshot.messages());
    }
}

final class InMemorySessionStore implements SessionStore {

    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>(); // 测试会话索引
    int backfillCount; // 测试 backfill 返回条数

    @Override
    public synchronized void create(String id, String title, Instant createdAt) {
        sessions.put(id, new StoredSession(title, createdAt, createdAt));
    }

    @Override
    public synchronized List<SessionSummary> list() {
        return sessions.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<String, StoredSession> entry) ->
                                entry.getValue().updatedAt())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> entry.getValue().summary(entry.getKey()))
                .toList();
    }

    @Override
    public synchronized Optional<SessionSummary> find(String id) {
        StoredSession stored = sessions.get(id);
        return stored == null
                ? Optional.empty()
                : Optional.of(stored.summary(id));
    }

    @Override
    public synchronized boolean rename(String id, String title) {
        StoredSession stored = sessions.get(id);
        if (stored == null) {
            return false;
        }
        sessions.put(id, new StoredSession(title, stored.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public synchronized boolean renameIfUnnamed(String id, String title) {
        StoredSession stored = sessions.get(id);
        if (stored == null || stored.title() != null) {
            return false;
        }
        sessions.put(id, new StoredSession(title, stored.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public synchronized void touch(String id) {
        StoredSession stored = sessions.get(id);
        if (stored != null) {
            sessions.put(id, new StoredSession(
                    stored.title(), stored.createdAt(), Instant.now()));
        }
    }

    @Override
    public synchronized boolean delete(String id) {
        return sessions.remove(id) != null;
    }

    @Override
    public synchronized int backfill(Instant createdAt) {
        return backfillCount;
    }

    synchronized Instant updatedAt(String id) {
        return sessions.get(id).updatedAt();
    }

    private record StoredSession(
            String title, Instant createdAt, Instant updatedAt) {

        private SessionSummary summary(String id) {
            return new SessionSummary(id, title, createdAt);
        }
    }
}
