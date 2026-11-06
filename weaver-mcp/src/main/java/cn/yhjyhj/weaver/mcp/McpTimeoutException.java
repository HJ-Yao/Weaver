package cn.yhjyhj.weaver.mcp;

/** MCP 请求在给定时限内未收到响应。 */
public final class McpTimeoutException extends McpException {

    /** 创建超时异常。 */
    public McpTimeoutException(String message) {
        super(message);
    }

    /** 创建带底层原因的超时异常。 */
    public McpTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
