package cn.yhjyhj.weaver.server.api.dto;

/**
 * 供会话事件时间线审计和回放使用的事件视图。
 *
 * <p>正文类字段在服务端按 code point 截断到 2000 字符，超出时追加省略号，
 * 避免历史事件列表因大输出而膨胀。
 *
 * @param seq 会话内单调递增的事件序号
 * @param type 前端用于选择展示文案的事件类型
 * @param role 消息事件的角色；非消息事件为 {@code null}
 * @param toolName 工具或审批事件的工具名；其余事件为 {@code null}
 * @param callId 工具或审批事件的调用标识；其余事件为 {@code null}
 * @param approved 审批结果；仅审批完成事件有值
 * @param aborted 运行是否被中止；仅运行结束事件有值
 * @param content 消息正文；仅消息事件有值
 * @param argumentsJson 模型给出的原始工具参数；仅工具开始事件有值
 * @param resultContent 工具结果正文；仅工具完成事件有值
 * @param resultError 工具结果是否失败；仅工具完成事件有值
 * @param reason 审批来源或拒绝原因；仅审批完成事件有值
 */
public record SessionEventView(
        long seq,
        String type,
        String role,
        String toolName,
        String callId,
        Boolean approved,
        Boolean aborted,
        String content,
        String argumentsJson,
        String resultContent,
        Boolean resultError,
        String reason) {}
