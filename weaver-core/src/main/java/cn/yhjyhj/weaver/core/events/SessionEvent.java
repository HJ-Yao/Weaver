package cn.yhjyhj.weaver.core.events;

import cn.yhjyhj.weaver.core.tool.Reversibility;

/**
 * 会话事件：一次 Agent 运行中发生的所有事实。
 *
 * sealed 保证事件集封闭且最小——新增事件类型必须显式改这里。
 * 刻意保持 M1 最小集（消息 / 工具调用 / 运行结束），复杂度留给将来确凿需要时。
 */
public sealed interface SessionEvent
        permits SessionEvent.MessageAdded, SessionEvent.ToolCallStarted,
                SessionEvent.ToolCallCompleted, SessionEvent.ApprovalRequested,
                SessionEvent.ApprovalResolved, SessionEvent.RunFinished {

    /** 消息角色。 */
    enum Role {
        /** 用户输入。 */
        USER,
        /** 模型响应。 */
        ASSISTANT,
        /** 工具执行结果。 */
        TOOL
    }

    /** 一条消息进入会话。 */
    record MessageAdded(Role role, String content) implements SessionEvent {}

    /** 模型要求调用工具：保留 callId、工具名与模型给出的原始参数 JSON。 */
    record ToolCallStarted(String callId, String toolName, String argumentsJson) implements SessionEvent {}

    /** 工具执行完成：按 callId 关联到对应的开始事件。 */
    record ToolCallCompleted(String callId, ToolResult result) implements SessionEvent {}

    /**
     * 一次工具调用进入审批。
     *
     * @param callId 工具调用标识
     * @param toolName 工具名称
     * @param reversibility 审批时采用的可回滚程度
     */
    record ApprovalRequested(
            String callId, String toolName, Reversibility reversibility)
            implements SessionEvent {}

    /**
     * 审批结束。
     *
     * @param callId 工具调用标识
     * @param approved 是否批准执行
     * @param reason 批准来源或拒绝来源
     */
    record ApprovalResolved(String callId, boolean approved, String reason)
            implements SessionEvent {}

    /** 一次运行结束；aborted 为 true 表示被中止而非自然结束。 */
    record RunFinished(boolean aborted) implements SessionEvent {}
}
