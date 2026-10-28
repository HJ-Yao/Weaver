package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.events.SessionEvents;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 管理会话元数据，并从事件事实源生成会话快照。 */
@Service
public class SessionService {

    private final Map<String, SessionSummary> sessions = new ConcurrentHashMap<>(); // 会话元数据索引
    private final SessionEventStore store; // 会话事件事实源

    /**
     * 创建会话服务。
     *
     * @param store 会话事件事实源
     */
    public SessionService(SessionEventStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 创建空会话。
     *
     * @param title 非空白会话标题
     * @return 新建会话的元数据
     * @throws IllegalArgumentException 标题为空或仅含空白时抛出
     */
    public SessionSummary create(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        SessionSummary summary = new SessionSummary(UUID.randomUUID().toString(), title, Instant.now());
        sessions.put(summary.id(), summary);
        return summary;
    }

    /**
     * 返回按创建时间升序排列的会话元数据快照。
     *
     * @return 不可修改的会话列表
     */
    public List<SessionSummary> list() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(SessionSummary::createdAt)
                        .thenComparing(SessionSummary::id))
                .toList();
    }

    /**
     * 查找会话元数据。
     *
     * @param id 会话标识
     * @return 存在时返回会话元数据，否则为空
     */
    public Optional<SessionSummary> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /**
     * 删除会话元数据；事件日志由事实源独立管理。
     *
     * @param id 会话标识
     * @return 是否删除了已有会话
     */
    public boolean delete(String id) {
        return sessions.remove(id) != null;
    }

    /**
     * 从 seq 0 回放指定会话的全部事件。
     *
     * @param id 会话标识
     * @return 当前事件流对应的快照
     */
    public SessionSnapshot snapshot(String id) {
        return SessionEvents.replay(store.from(id, 0L));
    }
}
