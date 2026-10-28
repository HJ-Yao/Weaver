package cn.yhjyhj.weaver.core.model;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.tool.ToolSpec;
import java.util.List;

/**
 * 一次模型调用的输入、历史快照与可用工具。
 *
 * @param input 本次用户输入
 * @param history 调用开始时的会话消息快照
 * @param tools 本次请求可调用的工具描述
 */
public record ModelRequest(
        String input,
        List<SessionSnapshot.Message> history,
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
     * @param input 本次用户输入
     * @param history 调用开始时的会话消息快照
     */
    public ModelRequest(String input, List<SessionSnapshot.Message> history) {
        this(input, history, List.of());
    }
}
