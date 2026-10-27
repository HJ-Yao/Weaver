package cn.yhjyhj.weaver.core.tool;

import cn.yhjyhj.weaver.core.events.ToolResult;

/** 可供 Agent 循环调用的工具边界。 */
public interface Tool {

    /**
     * 返回工具名；名称必须与模型给出的 toolName 一致。
     *
     * @return 注册表与模型共享的工具名
     */
    String name();

    /**
     * 执行工具并返回可供回放的结果。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @return 工具执行结果
     */
    ToolResult execute(String argumentsJson);

    /**
     * 返回是否允许与其他工具并行执行。
     *
     * @return 默认允许并行
     */
    default boolean parallelizable() {
        return true;
    }
}
