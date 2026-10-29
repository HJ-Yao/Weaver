package cn.yhjyhj.weaver.core.model;

import java.util.List;

/**
 * 模型可消费的一条历史消息。
 *
 * <p>这是模型请求历史，不是用于展示的 UI 快照。{@link Assistant#toolCalls()} 与后续
 * {@link Tool} 通过 {@code callId} 配对，调用方必须保留这一结构化语义。
 */
public sealed interface ModelMessage
        permits ModelMessage.User, ModelMessage.Assistant, ModelMessage.Tool {

    /** 用户输入；该消息只承载用户文本，不包含任何工具调用或结果语义。 */
    record User(String content) implements ModelMessage {}

    /**
     * 助手回复。
     *
     * @param content 助手文本；纯工具调用轮次没有文本事件，因此允许使用空串
     * @param toolCalls 本轮发起的工具调用；无工具时为空列表，构造时会复制为不可变列表
     */
    record Assistant(String content, List<StreamChunk.ToolCall> toolCalls)
            implements ModelMessage {
        public Assistant {
            // 历史会被多次读取并传给模型，禁止外部列表在请求期间修改调用集合。
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /**
     * 工具结果。
     *
     * @param callId 必须对应某条 {@link Assistant#toolCalls()} 中的调用标识
     * @param content 工具输出；失败结果同样要通过该字段回传模型
     * @param isError 是否表示工具执行失败
     */
    record Tool(String callId, String content, boolean isError) implements ModelMessage {}
}
