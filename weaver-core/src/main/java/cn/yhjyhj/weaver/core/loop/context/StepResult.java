package cn.yhjyhj.weaver.core.loop.context;

/**
 * 单步执行结果，明确区分自然完成与中止。
 *
 * @param finished 是否结束整个运行
 * @param aborted 是否因失败、取消或边界条件中止
 * @param text 本步可供调用方消费的文本
 */
public record StepResult(boolean finished, boolean aborted, String text) {}
