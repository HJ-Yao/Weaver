package cn.yhjyhj.weaver.core.events;

/**
 * 会话事件：一次 Agent 运行中发生的所有事实。
 *
 * sealed 保证事件集封闭且最小——新增事件类型必须显式改这里。
 * 刻意保持 M1 最小集（消息 / 工具调用 / 运行结束），复杂度留给将来确凿需要时。
 */
public sealed interface SessionEvent
        permits SessionEvent.MessageAdded, SessionEvent.ToolCallStarted,
                SessionEvent.ToolCallCompleted, SessionEvent.RunFinished {

    /** 消息角色。 */
    enum Role { USER, ASSISTANT }

    /** 一条消息进入会话。 */
    record MessageAdded(Role role, String content) implements SessionEvent {}

    /** 模型要求调用工具：保留 callId、工具名与模型给出的原始参数 JSON。 */
    record ToolCallStarted(String callId, String toolName, String argumentsJson) implements SessionEvent {}

    /** 工具执行完成：按 callId 关联到对应的开始事件。 */
    record ToolCallCompleted(String callId, ToolResult result) implements SessionEvent {}

    /** 一次运行结束；aborted 为 true 表示被中止而非自然结束。 */
    record RunFinished(boolean aborted) implements SessionEvent {}
}
