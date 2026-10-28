package cn.yhjyhj.weaver.core.events;

/** 一次工具执行的最终结果；保留原始正文、错误标记与耗时，供回放与调试。 */
public record ToolResult(String content, boolean isError, long durationMs) {}
