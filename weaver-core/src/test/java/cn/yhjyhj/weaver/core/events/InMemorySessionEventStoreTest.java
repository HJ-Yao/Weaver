package cn.yhjyhj.weaver.core.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 事件存储契约："现场"状态与"从零回放"状态必须一致。 */
class InMemorySessionEventStoreTest {

    @Test
    void appendsAssignMonotonicSequence() {
        var store = new InMemorySessionEventStore();
        assertEquals(1L, store.append("s1", new SessionEvent.RunFinished(false)));
        assertEquals(2L, store.append("s1", new SessionEvent.RunFinished(false)));
        assertEquals(1L, store.append("s2", new SessionEvent.RunFinished(false)));
    }

    @Test
    void replayFromZeroEqualsLiveState() {
        var store = new InMemorySessionEventStore();
        store.append("s1", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "加个接口"));
        store.append("s1", new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "好的"));
        store.append("s1", new SessionEvent.RunFinished(false));
        var live = SessionEvents.replay(store.from("s1", 0L));
        assertEquals("加个接口", live.messages().get(0).content());
        assertEquals(2, live.messages().size());
        assertFalse(live.aborted());
    }

    @Test
    void fromFiltersEventsAfterGivenSequence() {
        var store = new InMemorySessionEventStore();
        store.append("s1", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "第一条"));
        store.append("s1", new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "第二条"));
        var tail = store.from("s1", 1L);
        assertEquals(1, tail.size());
        assertEquals("第二条", ((SessionEvent.MessageAdded) tail.get(0).event()).content());
    }

    @Test
    void sessionsAreIsolated() {
        var store = new InMemorySessionEventStore();
        store.append("a", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "来自A"));
        store.append("b", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "来自B"));
        assertEquals(1, SessionEvents.replay(store.from("a", 0L)).messages().size());
        assertEquals(1, SessionEvents.replay(store.from("b", 0L)).messages().size());
    }

    @Test
    void lastSeqTracksStoredSequence() {
        var store = new InMemorySessionEventStore();
        store.append("s1", new SessionEvent.RunFinished(false));
        store.append("s1", new SessionEvent.RunFinished(false));
        assertEquals(2L, store.lastSeq("s1"));
        assertEquals(0L, store.lastSeq("unknown"));
    }
}