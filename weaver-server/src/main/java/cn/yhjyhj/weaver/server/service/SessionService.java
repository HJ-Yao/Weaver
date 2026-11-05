package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEvents;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.store.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** 管理持久化会话元数据，并从事件事实源生成会话快照。 */
@Slf4j
public class SessionService {

    /** 未命名会话对外的展示标题。 */
    private static final String DEFAULT_TITLE = "新会话";

    private final SessionStore sessions; // 持久化会话元数据
    private final SessionEventStore events; // 会话事件事实源
    private final Path defaultWorkspace; // 会话未覆盖工作目录时使用的进程默认值

    /**
     * 创建会话服务。
     *
     * @param sessions 会话元数据存储
     * @param events 会话事件事实源
     * @param defaultWorkspace 会话未设置工作目录时使用的默认目录
     */
    public SessionService(
            SessionStore sessions, SessionEventStore events, Path defaultWorkspace) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.events = Objects.requireNonNull(events, "events");
        this.defaultWorkspace = Objects.requireNonNull(defaultWorkspace, "defaultWorkspace")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 创建空会话；省略或留空标题时先以 null 落库，等待首轮后自动命名。
     *
     * @param title 可选的非空白会话标题
     * @return 新建会话的元数据；未命名时显示「新会话」
     */
    public SessionSummary create(String title) {
        String storedTitle = title == null || title.isBlank() ? null : title;
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SessionSummary summary = new SessionSummary(
                UUID.randomUUID().toString(), storedTitle, createdAt, null);
        sessions.create(summary.id(), storedTitle, createdAt);
        return displaySummary(summary);
    }

    /**
     * 返回按最近更新时间倒序排列的会话元数据快照。
     *
     * @return 不可修改的会话列表
     */
    public List<SessionSummary> list() {
        return sessions.list().stream()
                .map(this::displaySummary)
                .toList();
    }

    /**
     * 查找会话元数据。
     *
     * @param id 会话标识
     * @return 存在时返回会话元数据，否则为空
     */
    public Optional<SessionSummary> find(String id) {
        return sessions.find(id).map(this::displaySummary);
    }

    /**
     * 返回会话生效的工作目录；会话不存在时返回空。
     *
     * @param id 会话标识
     * @return 存储覆盖值或默认工作区的绝对路径
     */
    public Optional<Path> workspace(String id) {
        return sessions.find(id)
                .map(summary -> Path.of(effectiveWorkspace(summary)));
    }

    /**
     * 判断会话标题是否仍为空。
     *
     * @param id 会话标识
     * @return 会话存在且持久化标题为 null 时返回 true
     */
    public boolean isUnnamed(String id) {
        return sessions.find(id)
                .map(summary -> summary.title() == null)
                .orElse(false);
    }

    /**
     * 仅在标题为 null 时写入；返回是否写入成功。
     *
     * @param id 会话标识
     * @param title 自动生成的展示标题
     * @return 会话存在且原标题为 null 并完成写入时返回 true
     * @throws IllegalArgumentException 标题为空或仅含空白时抛出
     */
    public boolean renameIfUnnamed(String id, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return sessions.renameIfUnnamed(id, title);
    }

    /**
     * 手动修改会话标题。
     *
     * @param id 会话标识
     * @param title 非空白的展示标题
     * @return 会话存在并修改成功时为 true
     * @throws IllegalArgumentException 标题为空或仅含空白时抛出
     */
    public boolean rename(String id, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return sessions.rename(id, title);
    }

    /**
     * 更新会话工作目录；路径必须存在且是目录。
     *
     * <p>落库值取 {@link Path#toRealPath()}，因此会消解 {@code ..} 与符号链接，
     * 并保存为规范化绝对路径。会话不存在时返回 false。
     *
     * @param id 会话标识
     * @param rawPath 用户提供的目录路径
     * @return 会话存在并完成更新时为 true
     * @throws IllegalArgumentException 路径为空、不存在、不是目录或无法解析时抛出
     */
    public boolean updateWorkspace(String id, String rawPath) {
        if (sessions.find(id).isEmpty()) {
            return false;
        }
        Path resolved = resolveWorkspace(rawPath);
        return sessions.updateWorkspace(id, resolved.toString());
    }

    /**
     * 删除会话事件和元数据。
     *
     * <p>先删除事件事实，再删除元数据，避免调用方观察到已删除会话仍留有事件。
     *
     * @param id 会话标识
     * @return 是否删除了已有会话
     */
    public boolean delete(String id) {
        events.deleteAll(id);
        return sessions.delete(id);
    }

    /**
     * 刷新会话最近更新时间。
     *
     * @param id 会话标识；不存在时由存储实现忽略
     */
    public void touch(String id) {
        sessions.touch(id);
    }

    /**
     * 为已有事件但缺少元数据的会话补元数据。
     *
     * @return 新增的会话元数据条数
     */
    public int backfill() {
        int backfilled = sessions.backfill(Instant.now());
        if (backfilled > 0) {
            log.info("backfilled {} session metadata rows from event history", backfilled);
        }
        return backfilled;
    }

    /**
     * 撤回从 fromSeq 起（含）的全部可见事件。
     *
     * @param sessionId 会话标识
     * @param fromSeq 目标轮次第一条用户消息的原始事件序号
     * @return 目标存在、可见且为 USER 消息并已追加撤回事件时返回 true
     */
    public boolean deleteTurn(String sessionId, long fromSeq) {
        boolean targetsUserMessage = SessionEvents.visible(events.from(sessionId, 0L))
                .stream()
                .anyMatch(stored -> stored.seq() == fromSeq
                        && stored.event() instanceof SessionEvent.MessageAdded message
                        && message.role() == SessionEvent.Role.USER);
        if (!targetsUserMessage) {
            return false;
        }
        events.append(sessionId, new SessionEvent.TurnDeleted(fromSeq));
        return true;
    }

    /**
     * 从 seq 0 回放指定会话的全部事件。
     *
     * @param id 会话标识
     * @return 当前事件流对应的快照
     */
    public SessionSnapshot snapshot(String id) {
        return SessionEvents.replay(events.from(id, 0L));
    }

    /**
     * 从 seq 0 读取会话的真实事件序列。
     *
     * @param id 会话标识
     * @return 按 seq 升序排列的事件；会话不存在或没有事件时为空列表
     */
    public List<StoredEvent> events(String id) {
        return events.from(id, 0L);
    }

    private SessionSummary displaySummary(SessionSummary summary) {
        String title = summary.title() == null ? DEFAULT_TITLE : summary.title();
        String workspace = effectiveWorkspace(summary);
        if (title.equals(summary.title()) && workspace.equals(summary.workspace())) {
            return summary;
        }
        return new SessionSummary(summary.id(), title, summary.createdAt(), workspace);
    }

    private String effectiveWorkspace(SessionSummary summary) {
        return summary.workspace() == null
                ? defaultWorkspace.toString()
                : summary.workspace();
    }

    private static Path resolveWorkspace(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("workspace must not be blank");
        }
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(candidate) || !Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("workspace must be an existing directory");
        }
        try {
            return candidate.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace is not accessible", exception);
        }
    }
}
