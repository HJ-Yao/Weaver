package cn.yhjyhj.weaver.server.store;

import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用进程内会话元数据存储。
 *
 * <p>与 {@code InMemorySessionEventStore} 对应：只服务于不关心 SQLite 的单元测试，
 * 生产装配始终使用 {@link SqliteSessionStore}。
 */
public final class InMemorySessionStore implements SessionStore {

    /** 按会话标识保存的元数据。 */
    private final Map<String, Stored> sessions = new ConcurrentHashMap<>();

    @Override
    public synchronized void create(String id, String title, Instant createdAt) {
        sessions.put(id, new Stored(title, createdAt, createdAt));
    }

    @Override
    public synchronized List<SessionSummary> list() {
        return sessions.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<String, Stored> entry) ->
                                entry.getValue().updatedAt())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> entry.getValue().summary(entry.getKey()))
                .toList();
    }

    @Override
    public synchronized Optional<SessionSummary> find(String id) {
        Stored stored = sessions.get(id);
        return stored == null ? Optional.empty() : Optional.of(stored.summary(id));
    }

    @Override
    public synchronized boolean rename(String id, String title) {
        Stored stored = sessions.get(id);
        if (stored == null) {
            return false;
        }
        sessions.put(id, new Stored(title, stored.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public synchronized boolean renameIfUnnamed(String id, String title) {
        Stored stored = sessions.get(id);
        if (stored == null || stored.title() != null) {
            return false;
        }
        sessions.put(id, new Stored(title, stored.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public synchronized void touch(String id) {
        Stored stored = sessions.get(id);
        if (stored != null) {
            sessions.put(id, new Stored(stored.title(), stored.createdAt(), Instant.now()));
        }
    }

    @Override
    public synchronized boolean delete(String id) {
        return sessions.remove(id) != null;
    }

    @Override
    public synchronized int backfill(Instant createdAt) {
        return 0;
    }

    /** 单条会话元数据。 */
    private record Stored(String title, Instant createdAt, Instant updatedAt) {

        private SessionSummary summary(String id) {
            return new SessionSummary(id, title, createdAt);
        }
    }
}
