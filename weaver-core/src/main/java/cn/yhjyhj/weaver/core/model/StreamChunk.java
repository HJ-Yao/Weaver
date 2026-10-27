package cn.yhjyhj.weaver.core.model;

/** 模型流式响应中的一个分片。 */
public sealed interface StreamChunk
        permits StreamChunk.Text, StreamChunk.ToolCall, StreamChunk.Done, StreamChunk.Error {

    /** 本次模型调用结束的原因。 */
    enum StopReason {
        /** 模型自然停止。 */
        STOP,
        /** 达到输出长度上限，需要后续受控续写。 */
        LENGTH,
        /** 模型以错误状态结束。 */
        ERROR
    }

    /**
     * 一段可追加的文本增量。
     *
     * @param delta 本次新增的文本
     */
    record Text(String delta) implements StreamChunk {}

    /**
     * 模型请求调用工具；callId 用于与结果配对，argumentsJson 为原始参数。
     *
     * @param callId 工具调用标识
     * @param toolName 工具名称
     * @param argumentsJson 模型给出的原始参数 JSON
     */
    record ToolCall(String callId, String toolName, String argumentsJson) implements StreamChunk {}

    /**
     * 模型流正常结束。
     *
     * @param reason 结束原因
     */
    record Done(StopReason reason) implements StreamChunk {}

    /**
     * 模型流报告错误。
     *
     * @param message 错误说明
     */
    record Error(String message) implements StreamChunk {}
}
