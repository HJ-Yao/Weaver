package cn.yhjyhj.weaver.mcp;

/** MCP 协议或连接操作失败时抛出的运行时异常。 */
public class McpException extends RuntimeException {

    private final Integer code; // JSON-RPC 错误码；非协议错误时为空

    /** 创建不带 JSON-RPC 错误码的异常。 */
    public McpException(String message) {
        super(message);
        this.code = null;
    }

    /** 创建带 JSON-RPC 错误码的异常。 */
    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 创建带原因且不带 JSON-RPC 错误码的异常。 */
    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    /** 返回 JSON-RPC 错误码；非协议错误返回 null。 */
    public Integer code() {
        return code;
    }
}
