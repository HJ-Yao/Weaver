package cn.yhjyhj.weaver.core.approval;

import java.util.Objects;

/**
 * 一条审批规则。
 *
 * @param toolName 精确匹配的工具名；null 表示任意工具
 * @param operation 匹配 argumentsJson 中 op 字段的值；null 表示任意操作
 * @param decision 命中后的基础决策
 */
public record ApprovalRule(String toolName, String operation, ApprovalDecision decision) {

    /**
     * 创建审批规则。
     *
     * @throws NullPointerException decision 为 null 时抛出
     */
    public ApprovalRule {
        Objects.requireNonNull(decision, "decision");
    }
}
