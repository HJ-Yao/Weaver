package cn.yhjyhj.weaver.core.model;

import cn.yhjyhj.weaver.core.tool.ToolSpec;
import java.util.List;

/**
 * 一次模型调用的输入、结构化历史与可用工具。
 *
 * @param input 本轮用户输入；历史已包含之前的用户消息，调用方不要再次追加旧输入
 * @param history 已按事件顺序重建的模型历史；保留工具调用与结果的 callId 配对，调用方不应重排
 * @param tools 本次请求可调用的工具描述
 */
public record ModelRequest(
        String input,
        List<ModelMessage> history,
        List<ToolSpec> tools) {

    /**
     * 固定历史与工具清单，避免模型调用期间被外部列表修改。
     *
     * @throws NullPointerException 当历史或工具清单为 null 时抛出
     */
    public ModelRequest {
        history = List.copyOf(history);
        tools = List.copyOf(tools);
    }

    /**
     * 创建不含可用工具的请求，兼容只关心输入与历史的调用方。
     *
     * @param input 本轮用户输入
     * @param history 已按事件顺序重建的模型历史，调用方不应重排
     */
    public ModelRequest(String input, List<ModelMessage> history) {
        this(input, history, List.of());
    }
}
