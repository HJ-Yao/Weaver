package cn.yhjyhj.weaver.core.tool;

/**
 * 暴露给模型的工具描述。
 *
 * @param name 注册表与模型共享的工具名
 * @param description 面向模型的用途与副作用说明
 * @param parametersSchema 描述 argumentsJson 结构的 JSON Schema
 */
public record ToolSpec(String name, String description, String parametersSchema) {}
