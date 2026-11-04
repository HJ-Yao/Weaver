package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改待消费信箱条目文本的请求。
 *
 * @param content 替换后的用户输入；不能为空或仅含空白
 */
public record InboxContentRequest(@NotBlank String content) {}
