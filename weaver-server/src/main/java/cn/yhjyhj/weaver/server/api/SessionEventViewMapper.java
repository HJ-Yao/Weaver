package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.server.api.dto.SessionEventView;
import java.util.List;
import java.util.Objects;

/** 将会话事件事实映射为时间线 DTO。 */
public final class SessionEventViewMapper {

    /** 正文类字段允许返回的最大 code point 数。 */
    private static final int MAX_CONTENT_CODE_POINTS = 2000;

    private SessionEventViewMapper() {}

    /**
     * 按输入顺序映射已存储事件，并截断用于审计预览的正文。
     *
     * @param stored 按 seq 升序排列的已存储事件
     * @return 与输入一一对应的时间线视图
     */
    public static List<SessionEventView> from(List<StoredEvent> stored) {
        Objects.requireNonNull(stored, "stored");
        return stored.stream().map(SessionEventViewMapper::map).toList();
    }

    private static SessionEventView map(StoredEvent stored) {
        SessionEvent event = Objects.requireNonNull(stored, "stored").event();
        long seq = stored.seq();

        // sealed 事件集没有 default 分支；新增事件类型时编译器会强制更新映射。
        return switch (event) {
            case SessionEvent.MessageAdded message -> {
                String role = message.role().name();
                yield new SessionEventView(
                        seq,
                        switch (message.role()) {
                            case USER -> "USER_MESSAGE";
                            case ASSISTANT -> "ASSISTANT_MESSAGE";
                            case TOOL -> "TOOL_MESSAGE";
                        },
                        role,
                        null,
                        null,
                        null,
                        null,
                        truncateForPreview(message.content()),
                        null,
                        null,
                        null,
                        null);
            }
            case SessionEvent.ToolCallStarted started ->
                    new SessionEventView(
                            seq,
                            "TOOL_STARTED",
                            null,
                            started.toolName(),
                            started.callId(),
                            null,
                            null,
                            null,
                            truncateForPreview(started.argumentsJson()),
                            null,
                            null,
                            null);
            case SessionEvent.ToolCallCompleted completed ->
                    new SessionEventView(
                            seq,
                            "TOOL_COMPLETED",
                            null,
                            null,
                            completed.callId(),
                            null,
                            null,
                            null,
                            null,
                            truncateForPreview(completed.result().content()),
                            completed.result().isError(),
                            null);
            case SessionEvent.ApprovalRequested requested ->
                    new SessionEventView(
                            seq,
                            "APPROVAL_REQUESTED",
                            null,
                            requested.toolName(),
                            requested.callId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
            case SessionEvent.ApprovalResolved resolved ->
                    new SessionEventView(
                            seq,
                            "APPROVAL_RESOLVED",
                            null,
                            null,
                            resolved.callId(),
                            resolved.approved(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            truncateForPreview(resolved.reason()));
            case SessionEvent.RunFinished finished ->
                    new SessionEventView(
                            seq,
                            "RUN_FINISHED",
                            null,
                            null,
                            null,
                            null,
                            finished.aborted(),
                            null,
                            null,
                            null,
                            null,
                            null);
        };
    }

    // 按 code point 截断，避免从 UTF-16 代理对中间切开一个字符。
    private static String truncateForPreview(String content) {
        if (content == null
                || content.codePointCount(0, content.length()) <= MAX_CONTENT_CODE_POINTS) {
            return content;
        }
        int endIndex = content.offsetByCodePoints(0, MAX_CONTENT_CODE_POINTS);
        return content.substring(0, endIndex) + "…";
    }
}
