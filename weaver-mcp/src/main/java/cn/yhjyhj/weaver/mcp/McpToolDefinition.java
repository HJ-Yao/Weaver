package cn.yhjyhj.weaver.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 服务器公开的一个工具。
 *
 * @param name 服务器侧的原始工具名
 * @param title 展示用标题；没有时为 null
 * @param description 用途说明；没有时为 null
 * @param inputSchema 参数的 JSON Schema；服务器没给时为 {@code {"type":"object"}}
 * @param annotations 行为提示
 */
public record McpToolDefinition(
        String name,
        String title,
        String description,
        JsonNode inputSchema,
        McpToolAnnotations annotations) {}
