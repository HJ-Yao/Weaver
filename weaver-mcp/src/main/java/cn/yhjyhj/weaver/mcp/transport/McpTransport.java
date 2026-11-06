package cn.yhjyhj.weaver.mcp.transport;

import cn.yhjyhj.weaver.mcp.McpException;
import java.util.function.Consumer;

/** 一条双向消息通道；以后可增加 streamable HTTP 实现。 */
public interface McpTransport extends AutoCloseable {

    /**
     * 启动通道并开始投递入站消息。
     *
     * @param onMessage 每收到一条完整 JSON 文本调用一次
     * @param onClosed 通道关闭时调用一次；正常关闭传 null，异常关闭传原因
     */
    void start(Consumer<String> onMessage, Consumer<Throwable> onClosed);

    /** 发送一条完整 JSON 文本；通道已关闭时抛 McpException。 */
    void send(String json) throws McpException;

    /** 最近的诊断输出（如 stdio 的 stderr 尾部）；没有时为空串。 */
    String diagnostics();

    /** 幂等关闭。 */
    @Override
    void close();
}
