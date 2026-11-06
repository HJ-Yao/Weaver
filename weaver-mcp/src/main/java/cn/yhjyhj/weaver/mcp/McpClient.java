package cn.yhjyhj.weaver.mcp;

import cn.yhjyhj.weaver.mcp.jsonrpc.JsonRpcConnection;
import cn.yhjyhj.weaver.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 一个 MCP 服务器的客户端：握手、列工具、调用工具，并响应服务器发来的请求与通知。
 *
 * <p>一个实例只连接一次；关闭后不可重用。除 {@link #connect(Duration)} 外，其余方法都可以
 * 从多个线程并发调用。
 */
@Slf4j
public final class McpClient implements AutoCloseable {

    /** 客户端发起握手时声明的协议版本。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    /** 默认握手超时；与 Codex 的启动超时一致。 */
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    /** 默认单次请求超时；与 Pi 的请求超时一致。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 服务器握手时可以回应的协议版本。 */
    private static final List<String> SUPPORTED_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");
    /** tools/list 最多翻页次数，防止异常服务器无限返回 nextCursor。 */
    private static final int MAX_TOOL_PAGES = 20;

    private final McpTransport transport; // 本客户端独占的消息通道
    private final List<Path> roots; // 回复 roots/list 时告诉服务器的根目录
    private final Duration requestTimeout; // 普通请求的超时
    private final AtomicBoolean connected = new AtomicBoolean(); // 握手是否已完成
    private final AtomicBoolean closed = new AtomicBoolean(); // 客户端是否已关闭
    private volatile Runnable toolsChanged = () -> {}; // 工具列表变化时的回调
    private volatile JsonRpcConnection connection; // 握手开始后创建的连接

    /**
     * 创建尚未连接的客户端。
     *
     * @param transport 尚未启动的传输
     * @param roots 暴露给服务器的根目录；为 null 时视为空
     * @param requestTimeout 普通请求的超时；为 null 时使用 {@link #DEFAULT_REQUEST_TIMEOUT}
     */
    public McpClient(McpTransport transport, List<Path> roots, Duration requestTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    /**
     * 启动传输并完成握手：发送 initialize、校验协议版本、再发送 initialized 通知。
     *
     * @param startupTimeout 等待 initialize 响应的上限；为 null 时使用默认值
     * @return 服务器身份、协议版本与能力
     * @throws McpTimeoutException 握手超时；此时传输已关闭
     * @throws McpException 版本不受支持、响应结构无效或传输失败；异常信息附带服务器诊断输出
     */
    public McpServerInfo connect(Duration startupTimeout) {
        if (connection != null || closed.get()) {
            throw new McpException("MCP client already connected or closed");
        }
        Duration timeout = startupTimeout == null ? DEFAULT_STARTUP_TIMEOUT : startupTimeout;
        try {
            JsonRpcConnection rpc = new JsonRpcConnection(transport);
            connection = rpc;
            // 处理器必须在发送 initialize 之前注册，服务器可能在握手期间就发来请求。
            rpc.onRequest("roots/list", ignored -> rootsResult());
            rpc.onRequest("ping", ignored -> MAPPER.createObjectNode());
            rpc.onNotification("notifications/tools/list_changed", ignored -> toolsChanged.run());

            JsonNode response = rpc.request("initialize", initializeParams(), timeout);
            McpServerInfo serverInfo = readServerInfo(response);
            rpc.notify("notifications/initialized", null);
            connected.set(true);
            return serverInfo;
        } catch (McpTimeoutException timeoutException) {
            close();
            throw new McpTimeoutException(
                    timeoutException.getMessage() + diagnosticsSuffix(), timeoutException);
        } catch (RuntimeException exception) {
            close();
            throw new McpException(exception.getMessage() + diagnosticsSuffix(), exception);
        }
    }

    /**
     * 列出服务器的全部工具，自动跟随 nextCursor 翻页。
     *
     * @return 不可变的工具定义列表；超过 {@value #MAX_TOOL_PAGES} 页时只返回已读到的部分
     * @throws McpException 未连接或响应结构无效
     */
    public List<McpToolDefinition> listTools() {
        requireConnected();
        List<McpToolDefinition> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = connection.request("tools/list", params, requestTimeout);
            JsonNode pageTools = result.path("tools");
            if (!pageTools.isArray()) {
                throw new McpException("invalid tools/list result");
            }
            for (JsonNode tool : pageTools) {
                tools.add(toToolDefinition(tool));
            }

            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull() || next.asText().isEmpty()) {
                return List.copyOf(tools);
            }
            cursor = next.asText();
        }
        log.warn("MCP tools/list 达到 {} 页上限，剩余页面被忽略", MAX_TOOL_PAGES);
        return List.copyOf(tools);
    }

    /**
     * 调用服务器上的一个工具。
     *
     * @param name 服务器侧的原始工具名
     * @param arguments 工具参数；为 null 时不带 arguments 字段
     * @return 原始结果；content 块的类型映射由上层负责
     * @throws McpTimeoutException 调用超时
     * @throws McpException 未连接、协议错误或连接中断
     */
    public McpToolResult callTool(String name, JsonNode arguments) {
        requireConnected();
        ObjectNode params = MAPPER.createObjectNode().put("name", name);
        if (arguments != null) {
            params.set("arguments", arguments);
        }
        JsonNode result = connection.request("tools/call", params, requestTimeout);

        List<JsonNode> content = new ArrayList<>();
        JsonNode blocks = result.path("content");
        if (blocks.isArray()) {
            blocks.forEach(block -> content.add(block.deepCopy()));
        }
        return new McpToolResult(
                content, result.path("isError").asBoolean(false), result.get("structuredContent"));
    }

    /**
     * 发送心跳。
     *
     * @throws McpException 未连接、超时或连接中断
     */
    public void ping() {
        requireConnected();
        connection.request("ping", MAPPER.createObjectNode(), requestTimeout);
    }

    /**
     * 注册工具列表变化的回调；后注册的覆盖先注册的。
     *
     * @param listener 服务器发出 tools/list_changed 时调用；为 null 时清除
     */
    public void onToolsChanged(Runnable listener) {
        toolsChanged = listener == null ? () -> {} : listener;
    }

    /**
     * 返回连接是否可用：握手已完成、客户端未关闭、底层连接仍打开。
     *
     * @return 可以发起请求时为 true
     */
    public boolean isConnected() {
        JsonRpcConnection rpc = connection;
        return connected.get() && !closed.get() && rpc != null && rpc.isOpen();
    }

    /** 幂等关闭连接与底层传输。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        JsonRpcConnection rpc = connection;
        if (rpc == null) {
            transport.close();
        } else {
            rpc.close();
        }
    }

    private static ObjectNode initializeParams() {
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("roots", MAPPER.createObjectNode().put("listChanged", false));
        ObjectNode params = MAPPER.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", capabilities);
        params.set("clientInfo",
                MAPPER.createObjectNode().put("name", "weaver").put("version", "0.1.0"));
        return params;
    }

    private static McpServerInfo readServerInfo(JsonNode response) {
        String protocolVersion = response.path("protocolVersion").asText();
        if (!SUPPORTED_VERSIONS.contains(protocolVersion)) {
            throw new McpException("unsupported MCP protocol version: " + protocolVersion);
        }
        JsonNode implementation = response.path("serverInfo");
        if (!implementation.path("name").isTextual()
                || !implementation.path("version").isTextual()) {
            throw new McpException("invalid MCP initialize result");
        }
        return new McpServerInfo(
                implementation.path("name").asText(),
                implementation.path("version").asText(),
                protocolVersion,
                response.path("capabilities"),
                textOrNull(response, "instructions"));
    }

    private JsonNode rootsResult() {
        ArrayNode rootsNode = MAPPER.createArrayNode();
        for (Path root : roots) {
            ObjectNode entry = rootsNode.addObject().put("uri", root.toUri().toString());
            Path name = root.getFileName();
            if (name != null) {
                entry.put("name", name.toString());
            }
        }
        return MAPPER.createObjectNode().set("roots", rootsNode);
    }

    private static McpToolDefinition toToolDefinition(JsonNode tool) {
        if (!tool.path("name").isTextual()) {
            throw new McpException("invalid MCP tool definition");
        }
        JsonNode schema = tool.get("inputSchema");
        if (schema == null || schema.isNull()) {
            // 模型协议要求工具参数是对象 schema；服务器没给时按任意对象处理。
            schema = MAPPER.createObjectNode().put("type", "object");
        }
        JsonNode hints = tool.path("annotations");
        McpToolAnnotations annotations = new McpToolAnnotations(
                booleanOrNull(hints, "readOnlyHint"),
                booleanOrNull(hints, "destructiveHint"),
                booleanOrNull(hints, "idempotentHint"),
                booleanOrNull(hints, "openWorldHint"));
        return new McpToolDefinition(
                tool.path("name").asText(),
                textOrNull(tool, "title"),
                textOrNull(tool, "description"),
                schema,
                annotations);
    }

    private static Boolean booleanOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new McpException("MCP client is not connected");
        }
    }

    private String diagnosticsSuffix() {
        String diagnostics = transport.diagnostics();
        return diagnostics.isBlank() ? "" : "; diagnostics: " + diagnostics;
    }
}
