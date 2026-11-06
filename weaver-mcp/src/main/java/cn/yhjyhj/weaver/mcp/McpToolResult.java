package cn.yhjyhj.weaver.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 工具调用的原始结果块；内容映射留给上层模块处理。 */
public record McpToolResult(List<JsonNode> content, boolean isError, JsonNode structuredContent) {
    /** 冻结服务器返回的内容块列表。 */
    public McpToolResult {
        content = List.copyOf(content);
    }
}
