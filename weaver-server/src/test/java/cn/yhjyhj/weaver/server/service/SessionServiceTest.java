package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private InMemorySessionEventStore store; // 被测服务使用的事实源
    private SessionService service; // 会话元数据与快照服务

    @BeforeEach
    void setUp() {
        store = new InMemorySessionEventStore();
        service = new SessionService(store);
    }

    @Test
    void createsFindableSessionWithGeneratedId() {
        SessionSummary created = service.create("First session");

        UUID.fromString(created.id());
        assertEquals("First session", created.title());
        assertEquals(created, service.find(created.id()).orElseThrow());
    }

    @Test
    void listsSessionsByCreationTimeAscending() {
        SessionSummary first = service.create("First");
        SessionSummary second = service.create("Second");
        SessionSummary third = service.create("Third");

        List<SessionSummary> listed = service.list();
        List<java.time.Instant> creationTimes =
                listed.stream().map(SessionSummary::createdAt).toList();

        assertEquals(
                creationTimes.stream().sorted().toList(),
                creationTimes);
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
