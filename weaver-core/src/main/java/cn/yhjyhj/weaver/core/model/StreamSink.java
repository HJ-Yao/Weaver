package cn.yhjyhj.weaver.core.model;

/**
 * 模型流式输出的实时旁路；不落事件流，供 UI/SSE 使用。
 *
 * <p>线程契约：回调可能发生在模型流发布线程；实现必须线程安全，
 * 且不得长时间阻塞，否则会拖慢模型流消费。
 */
public interface StreamSink {

    /** 收到一段文本增量。 */
    void onDelta(String delta);

    /** 模型请求调用工具。 */
    void onToolCall(StreamChunk.ToolCall call);

    /** 模型流正常结束。 */
    void onDone(StreamChunk.StopReason reason);

    /** 模型流异常；默认空实现。 */
    default void onError(String message) {}
}
