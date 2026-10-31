package cn.yhjyhj.weaver.server.sse;

import cn.yhjyhj.weaver.core.events.SessionEventStore;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 将一次 HTTP 响应绑定为共享同一发送边界的实时输出与审批通道。 */
public final class SseChannel {

    private final SseSender sender; // 流式输出与审批事件共用的响应发送器

    private SseChannel(SseSender sender) {
        this.sender = sender;
    }

    /**
     * 为当前响应创建 SSE 通道。
     *
     * @param emitter 当前请求的 SSE 发射器
     * @return 复用同一 emitter 的通道
     */
    public static SseChannel bind(SseEmitter emitter) {
        SseEmitter requiredEmitter = Objects.requireNonNull(emitter, "emitter");
        return new SseChannel((eventName, data) -> requiredEmitter.send(
                SseEmitter.event().name(eventName).data(data)));
    }

    /**
     * 创建共享当前通道的模型实时输出接收器。
     *
     * @return SSE 流接收器
     */
    public SseStreamSink streamSink() {
        return new SseStreamSink(sender);
    }

    /**
     * 创建共享当前通道的交互审批闸门。
     *
     * @param timeout 单次审批等待上限
     * @return SSE 审批闸门
     */
    public SseApprovalGate approvalGate(Duration timeout) {
        return new SseApprovalGate(sender, timeout);
    }

    /**
     * 在事件落库后把工具完成结果转发到当前 SSE 连接。
     *
     * @param delegate 原始会话事件存储
     * @return 带工具结果转发的装饰器
     */
    public SessionEventStore withToolResultRelay(SessionEventStore delegate) {
        return new SseEventStore(delegate, sender);
    }
}
