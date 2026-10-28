package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建会话请求。
 *
 * @param title 会话标题；不能为空或仅含空白
 */
public record CreateSessionRequest(@NotBlank String title) {}
