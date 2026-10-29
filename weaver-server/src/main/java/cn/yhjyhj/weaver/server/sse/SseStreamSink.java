package cn.yhjyhj.weaver.server.sse;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 把模型实时回调转换为命名 SSE 事件。 */
public final class SseStreamSink implements StreamSink {

    private final SseSender sender; // 事件名与载荷的发送边界

    /**
     * 创建绑定到指定响应发射器的实时输出端。
     *
     * @param emitter 当前请求的 SSE 发射器
     */
    public SseStreamSink(SseEmitter emitter) {
        this(senderFor(emitter));
    }

    SseStreamSink(SseSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /** {@inheritDoc} */
    @Override
    public void onDelta(String delta) {
        send("delta", delta);
    }

    /** {@inheritDoc} */
    @Override
    public void onToolCall(StreamChunk.ToolCall call) {
        send("tool", call);
    }

    /** {@inheritDoc} */
    @Override
    public void onDone(StreamChunk.StopReason reason) {
        send("done", reason);
    }

    /** {@inheritDoc} */
    @Override
    public void onError(String message) {
        send("error", message);
    }

    private void send(String eventName, Object data) {
        try {
            // 审批与模型输出共享 sender，同一把锁保证单个 SSE 响应串行写入。
            synchronized (sender) {
                sender.send(eventName, data);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to send SSE event: " + eventName, exception);
        }
    }

    private static SseSender senderFor(SseEmitter emitter) {
        SseEmitter requiredEmitter = Objects.requireNonNull(emitter, "emitter");
        return (eventName, data) -> requiredEmitter.send(
                SseEmitter.event().name(eventName).data(data));
    }
}
