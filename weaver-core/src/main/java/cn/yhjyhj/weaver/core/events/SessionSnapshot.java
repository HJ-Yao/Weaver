package cn.yhjyhj.weaver.core.events;

import java.util.List;

/** 折叠（重放）事件的产物：会话某一时刻的可见状态。 */
public record SessionSnapshot(
        List<Message> messages,
        List<ToolCallRecord> toolCalls,
        List<ApprovalRecord> approvals,
        boolean aborted,
        long lastSeq) {

    /** 快照中的一条消息。 */
    public record Message(SessionEvent.Role role, String content) {}

    /** 一次已完成的工具调用；argumentsJson 为模型原始传参，result 为结构化结果。 */
    public record ToolCallRecord(
            String callId,
            String toolName,
            String argumentsJson,
            ToolResult result) {}

    /**
     * 一次审批事实。
     *
     * @param callId 工具调用标识
     * @param toolName 工具名称；缺少请求事件时为 {@code "unknown"}
     * @param approved 是否批准执行
     * @param reason 审批来源或拒绝原因
     */
    public record ApprovalRecord(
            String callId,
            String toolName,
            boolean approved,
            String reason) {}

    /** 空会话的初始快照。 */
    public static SessionSnapshot empty() {
        return new SessionSnapshot(List.of(), List.of(), List.of(), false, 0L);
    }
}
