package cn.yhjyhj.weaver.core.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现：core 自测底座，也供嵌入式/演示场景使用。 */
public final class InMemorySessionEventStore implements SessionEventStore {

    private final Map<String, List<StoredEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, Long> sequences = new ConcurrentHashMap<>();

    @Override
    public long append(String sessionId, SessionEvent event) {
        long seq = sequences.merge(sessionId, 1L, Long::sum);
        events.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new StoredEvent(seq, event));
        return seq;
    }

    @Override
    public List<StoredEvent> from(String sessionId, long afterSeq) {
        return events.getOrDefault(sessionId, List.of()).stream()
                .filter(stored -> stored.seq() > afterSeq)
                .toList();
    }

    @Override
    public long lastSeq(String sessionId) {
        return sequences.getOrDefault(sessionId, 0L);
    }
}