package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建会话请求。
 *
 * @param title 可省略的会话标题；省略时由首轮运行自动命名
 */
public record CreateSessionRequest(@Size(max = 60) String title) {}
