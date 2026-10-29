package cn.yhjyhj.weaver.core.approval;

import java.util.Objects;

/**
 * 单次裁决所需的运行期上下文。
 *
 * @param sessionId 当前会话标识
 * @param interactive 是否存在可询问用户的界面
 * @param grants 当前会话的授权记录
 */
public record ApprovalContext(
        String sessionId,
        boolean interactive,
        ApprovalGrantStore grants) {

    /** 校验上下文必需字段。 */
    public ApprovalContext {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(grants, "grants");
    }

    /**
     * 创建有 UI 的默认上下文。
     *
     * @param sessionId 当前会话标识
     * @param grants 当前会话的授权记录
     * @return 需要用户确认时保持 ASK 的上下文
     */
    public static ApprovalContext interactive(
            String sessionId, ApprovalGrantStore grants) {
        return new ApprovalContext(sessionId, true, grants);
    }

    /**
     * 创建无 UI 的上下文；未授权的 ASK 会被降级。
     *
     * @param sessionId 当前会话标识
     * @param grants 当前会话的授权记录
     * @return 未授权 ASK 降级为 DENY 的上下文
     */
    public static ApprovalContext unattended(
            String sessionId, ApprovalGrantStore grants) {
        return new ApprovalContext(sessionId, false, grants);
    }
}
