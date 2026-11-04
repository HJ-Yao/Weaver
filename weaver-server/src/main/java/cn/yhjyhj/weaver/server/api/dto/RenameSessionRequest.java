package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改会话标题请求。
 *
 * @param title 会话标题；不能为空或仅含空白，最多 60 个字符
 */
public record RenameSessionRequest(
        @NotBlank @Size(max = 60) String title) {}
