package cn.yhjyhj.weaver.core.loop.context;

/**
 * 单步执行的请求参数。
 *
 * @param sessionId 当前会话标识
 * @param input 本轮用户输入
 * @param step 从 0 开始的当前步数
 */
public record RunRequest(String sessionId, String input, int step) {}
