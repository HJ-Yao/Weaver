package cn.yhjyhj.weaver.server.sse;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * 在事件落库后把工具执行结果转发到当前 SSE 连接。
 *
 * <p>事实源优先：只有 delegate 成功分配 seq 后才发送 SSE；发送失败只记录调试日志，
 * 不影响事件持久化或返回序号。
 */
@Slf4j
public final class SseEventStore implements SessionEventStore {

    private final SessionEventStore delegate; // 原始会话事件存储
    private final SseSender sender; // 当前 SSE 响应的发送器

    SseEventStore(SessionEventStore delegate, SseSender sender) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /**
     * 先追加事件，再按事件类型决定是否转发工具结果。
     *
     * @param sessionId 会话标识
     * @param event 待持久化的会话事件
     * @return delegate 分配的单调递增 seq
     */
    @Override
    public long append(String sessionId, SessionEvent event) {
        long seq = delegate.append(sessionId, event);
        if (event instanceof SessionEvent.ToolCallCompleted completed) {
            sendToolResult(completed);
        }
        return seq;
    }

    /** {@inheritDoc} */
    @Override
    public List<StoredEvent> from(String sessionId, long afterSeq) {
        return delegate.from(sessionId, afterSeq);
    }

    /** {@inheritDoc} */
    @Override
    public long lastSeq(String sessionId) {
        return delegate.lastSeq(sessionId);
    }

    private void sendToolResult(SessionEvent.ToolCallCompleted completed) {
        ToolResult result = completed.result();
        ToolResultEvent payload = new ToolResultEvent(
                completed.callId(),
                result.content(),
                result.isError(),
                result.durationMs());
        try {
            // 与其他 SSE 事件共享发送器，串行写入避免并发调用破坏响应帧。
            synchronized (sender) {
                sender.send("tool_result", payload);
            }
        } catch (IOException exception) {
            log.debug(
                    "failed to send tool result: callId={}",
                    completed.callId(),
                    exception);
        }
    }

    private record ToolResultEvent(
            String callId,
            String content,
            boolean isError,
            long durationMs) {}
}
