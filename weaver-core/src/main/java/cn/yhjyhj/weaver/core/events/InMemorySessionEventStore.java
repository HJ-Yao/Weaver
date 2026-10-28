package cn.yhjyhj.weaver.core.events;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** 内存实现：core 自测底座，也供嵌入式/演示场景使用。 */
public final class InMemorySessionEventStore implements SessionEventStore {

    /** 各会话按 seq 排序的并发事件索引。 */
    private final Map<String, ConcurrentSkipListMap<Long, StoredEvent>> events =
            new ConcurrentHashMap<>();
    /** 各会话已分配的 seq 上界。 */
    private final Map<String, Long> sequences = new ConcurrentHashMap<>();

    /**
     * 追加事件并分配会话内单调递增的 seq。
     *
     * <p>并发安全：多个线程可同时为同一会话追加事件，已分配的 seq 不重复，
     * 事件按 seq 排序存储。
     */
    @Override
    public long append(String sessionId, SessionEvent event) {
        long seq = sequences.merge(sessionId, 1L, Long::sum);
        events.computeIfAbsent(sessionId, key -> new ConcurrentSkipListMap<>())
                .put(seq, new StoredEvent(seq, event));
        return seq;
    }

    @Override
    public List<StoredEvent> from(String sessionId, long afterSeq) {
        NavigableMap<Long, StoredEvent> sessionEvents = events.get(sessionId);
        if (sessionEvents == null) {
            return List.of();
        }
        return List.copyOf(sessionEvents.tailMap(afterSeq, false).values());
    }

    /**
     * 返回会话已分配的最高 seq。
     *
     * <p>与并发 {@link #append(String, SessionEvent)} 之间是最终一致：
     * 读取时可能尚未观察到刚分配的 seq。
     */
    @Override
    public long lastSeq(String sessionId) {
        return sequences.getOrDefault(sessionId, 0L);
    }
}
