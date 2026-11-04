package cn.yhjyhj.weaver.core.loop.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话输入信箱：线程安全的待处理输入，分「本轮插话」与「下一轮排队」两条道。
 *
 * <p>两条道共用同一个到达顺序队列，{@link Target} 只标记条目的生效位置。所有公开方法
 * 都可从任意线程调用；实例通过同一个内部锁串行化读写，不暴露内部集合。
 */
public final class Inbox {

    /** 默认容量上限。 */
    public static final int DEFAULT_CAPACITY = 32;

    /** 输入生效目标。 */
    public enum Target {
        /** 当前轮的下一个 step 前注入。 */
        STEP,
        /** 当前轮结束后作为下一轮输入。 */
        TURN
    }

    /**
     * 一条待处理输入。
     *
     * @param id 实例内唯一标识
     * @param target 生效目标
     * @param content 原始用户输入
     */
    public record Entry(String id, Target target, String content) {}

    /** 两条道共享的待处理条目，保留全局到达顺序。 */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** 保护 entries、容量判定与标识生成的实例锁。 */
    private final Object lock = new Object();
    /** 本实例可容纳的最大条目数。 */
    private final int capacity;
    /** 生成实例内递增输入标识。 */
    private final AtomicLong nextId = new AtomicLong();

    /** 使用默认容量创建空信箱。 */
    public Inbox() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定容量创建空信箱。
     *
     * @param capacity 最大条目数
     */
    public Inbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * 追加一条输入。
     *
     * @param content 用户输入，空白内容会被忽略
     * @param target 生效目标
     * @return 成功入队的新条目；空白或已满时返回 null
     */
    public Entry offer(String content, Target target) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(target, "target");
        if (content.isBlank()) {
            return null;
        }
        synchronized (lock) {
            if (entries.size() >= capacity) {
                return null;
            }
            Entry entry =
                    new Entry("in-" + nextId.incrementAndGet(), target, content);
            entries.addLast(entry);
            return entry;
        }
    }

    /**
     * 取出全部 STEP 条目并移除。
     *
     * @return 按到达顺序排列的 STEP 条目；没有时返回空列表
     */
    public List<Entry> drainStep() {
        synchronized (lock) {
            List<Entry> drained = new ArrayList<>();
            Iterator<Entry> iterator = entries.iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.target() == Target.STEP) {
                    drained.add(entry);
                    iterator.remove();
                }
            }
            return List.copyOf(drained);
        }
    }

    /**
     * 取出并移除最早的一条 TURN 条目。
     *
     * @return 最早到达的 TURN 条目；没有时返回 null
     */
    public Entry pollTurn() {
        synchronized (lock) {
            Iterator<Entry> iterator = entries.iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.target() == Target.TURN) {
                    iterator.remove();
                    return entry;
                }
            }
            return null;
        }
    }

    /** 把尚未消费的 STEP 条目原地降级为 TURN，并保留全局到达顺序。 */
    public void promoteStepToTurn() {
        synchronized (lock) {
            if (entries.stream().noneMatch(entry -> entry.target() == Target.STEP)) {
                return;
            }
            ArrayDeque<Entry> promoted = new ArrayDeque<>(entries.size());
            for (Entry entry : entries) {
                promoted.addLast(entry.target() == Target.STEP
                        ? new Entry(entry.id(), Target.TURN, entry.content())
                        : entry);
            }
            entries.clear();
            entries.addAll(promoted);
        }
    }

    /**
     * 按 id 删除一条未消费输入。
     *
     * @param id 条目标识
     * @return 是否找到并删除
     */
    public boolean remove(String id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            Iterator<Entry> iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().id().equals(id)) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 按 id 替换未消费输入的文本，目标保持不变。
     *
     * @param id 条目标识
     * @param content 新文本，空白内容视为非法
     * @return 是否找到并替换
     */
    public boolean replace(String id, String content) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) {
            return false;
        }
        synchronized (lock) {
            ArrayDeque<Entry> updated = new ArrayDeque<>(entries.size());
            boolean found = false;
            for (Entry entry : entries) {
                if (entry.id().equals(id)) {
                    updated.addLast(new Entry(entry.id(), entry.target(), content));
                    found = true;
                } else {
                    updated.addLast(entry);
                }
            }
            if (!found) {
                return false;
            }
            entries.clear();
            entries.addAll(updated);
            return true;
        }
    }

    /**
     * 返回当前未消费条目的只读快照。
     *
     * @return 按到达顺序排列的不可变条目列表
     */
    public List<Entry> snapshot() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    /**
     * 返回当前未消费条目数。
     *
     * @return 条目数
     */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * 判断是否仍有未消费输入。
     *
     * @return 任一目标仍有条目时为 true
     */
    public boolean hasPending() {
        synchronized (lock) {
            return !entries.isEmpty();
        }
    }

    /** 清空全部未消费输入。 */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }
}
