package cn.yhjyhj.weaver.core.approval;

import java.util.Objects;

/**
 * 一次审批的最终结论与可审计原因。
 *
 * @param decision 最终审批结论
 * @param reason 稳定、可供审计的事实原因
 */
public record ApprovalResult(ApprovalDecision decision, String reason) {

    /** 保证结论和审计原因始终存在，避免调用方用 null 隐式放行。 */
    public ApprovalResult {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
    }
}
