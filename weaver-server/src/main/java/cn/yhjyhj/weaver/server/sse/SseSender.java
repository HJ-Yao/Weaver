package cn.yhjyhj.weaver.server.sse;

import java.io.IOException;

/** 发送一条具名 SSE 事件的最小传输边界。 */
@FunctionalInterface
interface SseSender {

    /**
     * 同步发送一条事件。
     *
     * @param eventName SSE 事件名
     * @param data 可序列化的事件载荷
     * @throws IOException 客户端断开或底层响应写入失败时抛出
     */
    void send(String eventName, Object data) throws IOException;
}
