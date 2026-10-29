package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 用户对一次待审批工具调用的决策。
 *
 * @param approved 是否批准执行
 */
public record ApprovalDecisionRequest(@NotNull Boolean approved) {}
