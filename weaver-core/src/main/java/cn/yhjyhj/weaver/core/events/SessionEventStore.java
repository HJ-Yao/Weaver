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
}