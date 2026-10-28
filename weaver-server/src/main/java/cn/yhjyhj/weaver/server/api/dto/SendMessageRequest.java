package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 向会话发送一条用户消息。
 *
 * @param content 用户输入；不能为空或仅含空白
 */
public record SendMessageRequest(@NotBlank String content) {}
