package cn.yhjyhj.weaver.server.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新会话标题与工作目录的请求。
 *
 * <p>两个字段均可省略，但至少一个必须非空白。标题最长 60 个字符。
 *
 * @param title 新标题；可空，存在且非空白时更新
 * @param workspace 新的工作目录绝对路径；可空，存在时校验并更新
 */
public record UpdateSessionRequest(@Size(max = 60) String title, String workspace) {}
