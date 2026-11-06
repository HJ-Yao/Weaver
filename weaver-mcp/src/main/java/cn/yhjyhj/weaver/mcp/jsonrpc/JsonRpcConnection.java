package cn.yhjyhj.weaver.mcp.jsonrpc;

import cn.yhjyhj.weaver.mcp.McpException;
import cn.yhjyhj.weaver.mcp.McpTimeoutException;
import cn.yhjyhj.weaver.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * 在消息通道上实现 JSON-RPC 2.0 的请求、响应与通知分派。
 *
 * <p>请求按自增 id 登记到挂起表，响应回来时按 id 取出对应的 future，因此并发请求互不串扰。
 * 通道关闭后，所有挂起请求立即失败，之后的请求也直接失败。服务器发来的请求与通知在虚拟线程中处理，
 * 不阻塞读取线程。
 */
@Slf4j
public final class JsonRpcConnection implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON-RPC 标准错误码：方法不存在。 */
    private static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC 标准错误码：内部错误。 */
    private static final int INTERNAL_ERROR = -32603;

    private final McpTransport transport; // 底层消息通道
    private final AtomicLong nextId = new AtomicLong(1); // 本连接内单调递增的请求编号
    private final AtomicBoolean closed = new AtomicBoolean(); // 连接是否已关闭
    private volatile String closeReason = "normal"; // 关闭原因，写进后续请求的错误信息

    /** 等待响应的请求，按请求编号索引。 */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** 服务器通知的处理器，按方法名索引。 */
    private final Map<String, Consumer<JsonNode>> notificationHandlers = new ConcurrentHashMap<>();
    /** 服务器请求的处理器，按方法名索引。 */
    private final Map<String, Function<JsonNode, JsonNode>> requestHandlers =
            new ConcurrentHashMap<>();

    /**
     * 创建连接并立即启动传输。
     *
     * @param transport 尚未启动的消息通道
     */
    public JsonRpcConnection(McpTransport transport) {
        this.transport = transport;
        transport.start(this::dispatch, this::handleClosed);
    }

    /**
     * 发送请求并等待对应响应。
     *
     * <p>超时后从挂起表移除该请求，并给服务器发送 {@code notifications/cancelled}，
     * 让它停止处理；取消通知发送失败不影响超时异常。
     *
     * @param method 方法名
     * @param params 参数；为 null 时不带 params 字段
     * @param timeout 等待响应的上限
     * @return 响应的 result 字段
     * @throws McpTimeoutException 等待超时
     * @throws McpException 服务器返回 error、连接已关闭或发送失败
     */
    public JsonNode request(String method, JsonNode params, Duration timeout) {
        ensureOpen();
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        // 登记与关闭可能交错：关闭若发生在 ensureOpen 之后、登记之前，这个请求不会被关闭流程唤醒。
        if (closed.get()) {
            pending.remove(id, future);
            throw new McpException("connection closed: " + closeReason);
        }

        ObjectNode message = baseMessage().put("id", id).put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        try {
            transport.send(message.toString());
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.remove(id, future);
            sendCancellation(id);
            throw new McpTimeoutException("MCP request timed out: " + method);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pending.remove(id, future);
            throw new McpException("MCP request interrupted: " + method, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new McpException("MCP request failed: " + method, cause);
        } catch (McpException exception) {
            pending.remove(id, future);
            throw exception;
        }
    }

    /**
     * 发送不需要响应的通知。
     *
     * @param method 方法名
     * @param params 参数；为 null 时不带 params 字段
     */
    public void notify(String method, JsonNode params) {
        ensureOpen();
        ObjectNode message = baseMessage().put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        transport.send(message.toString());
    }

    /**
     * 注册服务器通知的处理器；同名方法后注册的覆盖先注册的。
     *
     * @param method 通知方法名
     * @param handler 接收通知 params 的处理器
     */
    public void onNotification(String method, Consumer<JsonNode> handler) {
        notificationHandlers.put(method, handler);
    }

    /**
     * 注册服务器请求的处理器；同名方法后注册的覆盖先注册的。
     *
     * @param method 请求方法名
     * @param handler 接收请求 params、返回 result 的处理器
     */
    public void onRequest(String method, Function<JsonNode, JsonNode> handler) {
        requestHandlers.put(method, handler);
    }

    /**
     * 返回连接是否仍可发送请求。
     *
     * @return 未关闭时为 true
     */
    public boolean isOpen() {
        return !closed.get();
    }

    /** 关闭连接与底层通道；挂起请求立即失败。 */
    @Override
    public void close() {
        handleClosed(null);
        transport.close();
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = MAPPER.readTree(text);
        } catch (Exception exception) {
            log.warn("忽略无法解析的 JSON-RPC 消息: {}", exception.getMessage());
            return;
        }
        if (message == null || !"2.0".equals(message.path("jsonrpc").asText())) {
            log.warn("忽略缺少 jsonrpc 2.0 标记的消息");
            return;
        }

        JsonNode id = message.get("id");
        boolean hasMethod = message.has("method");
        if (id != null && (message.has("result") || message.has("error"))) {
            handleResponse(id, message);
        } else if (hasMethod && id != null) {
            Thread.startVirtualThread(() -> handleRequest(id, message));
        } else if (hasMethod) {
            handleNotification(message);
        } else {
            log.warn("忽略结构无效的 JSON-RPC 消息: {}", text);
        }
    }

    private void handleResponse(JsonNode idNode, JsonNode message) {
        if (!idNode.canConvertToLong()) {
            return;
        }
        // 超时后才到达的响应找不到挂起项，直接丢弃。
        CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
        if (future == null) {
            return;
        }
        if (message.has("error")) {
            JsonNode error = message.path("error");
            future.completeExceptionally(new McpException(
                    error.path("code").asInt(INTERNAL_ERROR),
                    error.path("message").asText("JSON-RPC error")));
        } else {
            future.complete(message.get("result"));
        }
    }

    private void handleRequest(JsonNode id, JsonNode message) {
        Function<JsonNode, JsonNode> handler =
                requestHandlers.get(message.path("method").asText());
        ObjectNode response = baseMessage().set("id", id.deepCopy());
        if (handler == null) {
            response.set("error", errorObject(METHOD_NOT_FOUND, "Method not found"));
        } else {
            try {
                JsonNode result = handler.apply(message.get("params"));
                response.set("result", result == null ? MAPPER.createObjectNode() : result);
            } catch (Throwable exception) {
                String reason = exception.getMessage() == null
                        ? "Internal error"
                        : exception.getMessage();
                response.set("error", errorObject(INTERNAL_ERROR, reason));
            }
        }
        try {
            transport.send(response.toString());
        } catch (McpException exception) {
            log.debug("服务端请求的响应发送失败", exception);
        }
    }

    private void handleNotification(JsonNode message) {
        Consumer<JsonNode> handler = notificationHandlers.get(message.path("method").asText());
        if (handler != null) {
            Thread.startVirtualThread(() -> handler.accept(message.get("params")));
        }
    }

    private void sendCancellation(long id) {
        ObjectNode params = MAPPER.createObjectNode().put("requestId", id).put("reason", "timeout");
        ObjectNode cancellation = baseMessage().put("method", "notifications/cancelled");
        cancellation.set("params", params);
        try {
            transport.send(cancellation.toString());
        } catch (McpException ignored) {
            // 通道可能已断开；超时本身已经是要报告的结果。
        }
    }

    private void handleClosed(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeReason = cause == null ? "normal" : cause.getMessage();
        McpException failure = new McpException("connection closed: " + closeReason, cause);
        pending.forEach((id, future) -> future.completeExceptionally(failure));
        pending.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new McpException("connection closed: " + closeReason);
        }
    }

    private static ObjectNode baseMessage() {
        return MAPPER.createObjectNode().put("jsonrpc", "2.0");
    }

    private static ObjectNode errorObject(int code, String message) {
        return MAPPER.createObjectNode().put("code", code).put("message", message);
    }
}
