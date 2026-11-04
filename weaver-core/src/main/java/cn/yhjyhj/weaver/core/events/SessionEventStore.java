package cn.yhjyhj.weaver.core.events;

import java.util.List;

/**
 * 事件存储契约：只追加的会话事件日志。
 *
 * 实现方（内存 / SQLite / MySQL）自行决定序列化与索引；
 * 契约只保证：seq 单调、按 afterSeq 过滤、lastSeq 可查。
 */
public interface SessionEventStore {

    /** 追加事件并返回其单调递增的 seq。 */
    long append(String sessionId, SessionEvent event);

    /** 返回 seq 严格大于 afterSeq 的所有事件，升序。 */
    List<StoredEvent> from(String sessionId, long afterSeq);

    /** 会话当前的最高 seq；空会话为 0。 */
    long lastSeq(String sessionId);

    /**
     * 返回有事件的会话标识，按标识升序排列。
     *
     * <p>默认实现用于兼容不提供会话枚举能力的旧存储；调用时抛出
     * {@link UnsupportedOperationException}。
     */
    default List<String> sessionIds() {
        throw new UnsupportedOperationException("sessionIds is not supported");
    }

    /**
     * 删除会话的全部事件与序号状态；不存在时什么也不做。
     *
     * <p>默认实现用于兼容不支持删除的旧存储；调用时抛出
     * {@link UnsupportedOperationException}。新实现应覆盖此方法。
     */
    default void deleteAll(String sessionId) {
        throw new UnsupportedOperationException("deleteAll is not supported");
    }
}
