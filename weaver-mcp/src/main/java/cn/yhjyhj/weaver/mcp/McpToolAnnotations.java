package cn.yhjyhj.weaver.mcp;

/**
 * MCP 工具的行为提示；服务器没给的字段保持 null，含义由上层审批策略解释。
 *
 * @param readOnlyHint 是否只读
 * @param destructiveHint 是否可能造成破坏性修改
 * @param idempotentHint 重复调用是否无额外影响
 * @param openWorldHint 是否会访问外部世界（网络、第三方服务）
 */
public record McpToolAnnotations(
        Boolean readOnlyHint,
        Boolean destructiveHint,
        Boolean idempotentHint,
        Boolean openWorldHint) {}
