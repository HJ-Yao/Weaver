package cn.yhjyhj.weaver.core.approval;

import java.util.Objects;
import java.util.regex.Pattern;

/** 一条审批规则；toolPattern 为工具名正则，argsPattern 为参数 JSON 正则。 */
public record ApprovalRule(
        String toolPattern, String argsPattern, ApprovalDecision decision) {

    /**
     * 创建并校验审批规则。
     *
     * @param toolPattern 完整匹配工具名的正则
     * @param argsPattern 完整匹配原始参数 JSON 的正则
     * @param decision 两个正则均命中时的基础决策
     * @throws IllegalArgumentException 任一正则非法时抛出
     */
    public ApprovalRule {
        Objects.requireNonNull(toolPattern, "toolPattern");
        Objects.requireNonNull(argsPattern, "argsPattern");
        Objects.requireNonNull(decision, "decision");
        // 在边界处立即暴露配置错误，避免会话执行到工具调用时才发现。
        Pattern.compile(toolPattern);
        Pattern.compile(argsPattern);
    }
}
