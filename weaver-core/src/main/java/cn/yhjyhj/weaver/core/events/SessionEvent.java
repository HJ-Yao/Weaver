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

    /** 一次工具调用开始（记录名称与参数摘要）。 */
    record ToolCallStarted(String toolName, String argsSummary) implements SessionEvent {}

    /** 一次工具调用结束（记录名称与结果摘要）。 */
    record ToolCallCompleted(String toolName, String resultSummary) implements SessionEvent {}

    /** 一次运行结束；aborted 为 true 表示被中止而非自然结束。 */
    record RunFinished(boolean aborted) implements SessionEvent {}
}