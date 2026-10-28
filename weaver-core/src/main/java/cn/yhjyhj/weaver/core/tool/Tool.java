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
     * 返回面向模型的用途说明。
     *
     * <p>默认值与工具名相同，内置工具必须覆盖为能说明使用时机和副作用的文本。
     *
     * @return 工具用途说明
     */
    default String description() {
        return name();
    }

    /**
     * 返回描述 argumentsJson 结构的 JSON Schema 字符串。
     *
     * <p>默认 schema 表示一个不限制字段的对象，内置工具必须覆盖为精确契约。
     *
     * @return argumentsJson 的 JSON Schema
     */
    default String parametersSchema() {
        return "{\"type\":\"object\"}";
    }

    /**
     * 执行工具并返回可供回放的结果。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @return 工具执行结果
     */
    ToolResult execute(String argumentsJson);

    /**
     * 按本次原始参数返回工具操作的回滚程度。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @return 本次调用的回滚性；默认回退到无参声明
     */
    default Reversibility reversibility(String argumentsJson) {
        return reversibility();
    }

    /**
     * 按本次原始参数返回工具是否允许与其他工具并行执行。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @return 本次调用是否可并行；默认回退到无参声明
     */
    default boolean parallelizable(String argumentsJson) {
        return parallelizable();
    }

    /**
     * 返回不带参数上下文时是否允许并行执行。
     *
     * @return 默认允许并行
     */
    default boolean parallelizable() {
        return true;
    }

    /**
     * 返回不带参数上下文时的回滚程度。
     *
     * @return 默认不可回滚
     */
    default Reversibility reversibility() {
        return Reversibility.IRREVERSIBLE;
    }
}
