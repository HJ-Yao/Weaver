package cn.yhjyhj.weaver.mcp.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 以 stdin/stdout 模拟指定行为的 MCP 服务器。 */
public final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String mode; // 当前行为模式
    private static int toolPage; // 已返回工具列表页数
    private static JsonNode rootsCallId; // 等待根目录回复的工具调用编号

    private FakeMcpServer() { }

    /** 启动测试用协议服务器。 */
    public static void main(String[] args) throws Exception {
        mode = args[0];
        if (mode.equals("child-sleeper")) {
            Thread.sleep(TimeUnit.MINUTES.toMillis(2));
            return;
        }
        if (mode.equals("slow-init")) Thread.sleep(5_000);
        if (mode.equals("crash-immediately")) {
            System.err.println("startup failed");
            System.exit(23);
        }
        if (mode.equals("stderr-tail")) System.err.println("x".repeat(9_000));
        if (mode.equals("oversized-line")) {
            byte[] block = new byte[3_000];
            for (int index = 0; index < block.length; index += 3) {
                block[index] = (byte) 0xE7;
                block[index + 1] = (byte) 0x95;
                block[index + 2] = (byte) 0x8C;
            }
            for (int index = 0; index < 5_600; index++) System.out.write(block);
            System.out.write('\n');
            System.out.flush();
        }
        if (mode.equals("spawn-child")) spawnChild();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode message = MAPPER.readTree(line);
                String method = message.path("method").asText();
                if (method.equals("initialize")) {
                    initialize(message);
                    if (mode.equals("list-changed")) send(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"));
                } else if (method.equals("tools/list")) listTools(message);
                else if (method.equals("tools/call")) callTool(message);
                else if (method.equals("ping")) reply(message, MAPPER.createObjectNode());
                else if (message.has("id") && rootsCallId != null) {
                    finishRootsCall(message);
                }
                else if (method.equals("notifications/cancelled")) {
                    System.err.println("cancelled=" + message.path("params").path("requestId").asLong());
                    System.err.flush();
                } else if (method.equals("notifications/initialized")) {
                    System.err.println("initialized");
                    System.err.flush();
                }
            }
        }
    }

    private static void initialize(JsonNode request) throws Exception {
        String version = mode.equals("unsupported-version") ? "2099-01-01" : "2025-06-18";
        ObjectNode result = MAPPER.createObjectNode().put("protocolVersion", version);
        result.set("capabilities", MAPPER.createObjectNode().set("tools", MAPPER.createObjectNode().put("listChanged", true)));
        if (!mode.equals("invalid-initialize")) result.set("serverInfo", MAPPER.createObjectNode().put("name", "fake").put("version", "1.2.3"));
        result.put("instructions", "test server");
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        send(response);
    }

    private static void listTools(JsonNode request) throws Exception {
        toolPage++;
        ObjectNode result = MAPPER.createObjectNode();
        if (mode.equals("invalid-tools")) {
            result.put("tools", "invalid");
            reply(request, result);
            return;
        }
        ArrayNode tools = result.putArray("tools");
        if (mode.equals("paged-tools") || mode.equals("endless-pages")) {
            ObjectNode tool = tools.addObject().put("name", "page-" + toolPage);
            tool.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
            if (mode.equals("paged-tools") && toolPage < 3 || mode.equals("endless-pages")) result.put("nextCursor", "cursor-" + toolPage);
        } else {
            ObjectNode tool = tools.addObject().put("name", "echo");
            if (!mode.equals("missing-schema")) tool.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
            if (mode.equals("rich-tool")) {
                tool.put("title", "Echo title").put("description", "Echo description");
                tool.set("annotations", MAPPER.createObjectNode().put("readOnlyHint", true).put("destructiveHint", false)
                        .put("idempotentHint", true).put("openWorldHint", false));
            }
        }
        reply(request, result);
    }

    private static void callTool(JsonNode request) throws Exception {
        if (mode.equals("crash-after-init")) System.exit(17);
        if (mode.equals("hang-on-call")) return;
        JsonNode params = request.path("params");
        JsonNode value = params.path("arguments");
        if (mode.equals("ask-roots")) {
            send(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 991).put("method", "roots/list"));
            rootsCallId = request.get("id").deepCopy();
            return;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", mode.equals("tool-error") ? "failed" : value.toString());
        if (mode.equals("tool-error")) result.put("isError", true);
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0").set("id", request.get("id"));
        response.set("result", result);
        send(response);
    }

    private static void finishRootsCall(JsonNode rootsResponse) throws Exception {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", rootsResponse.path("result").path("roots").toString());
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0").set("id", rootsCallId);
        response.set("result", result);
        rootsCallId = null;
        send(response);
    }

    private static void spawnChild() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classPath = System.getProperty("java.class.path");
        System.err.println("server-pid=" + ProcessHandle.current().pid());
        Process sleeper = new ProcessBuilder(java, "-cp", classPath, FakeMcpServer.class.getName(), "child-sleeper").start();
        System.err.println("sleeper-pid=" + sleeper.pid());
        System.err.flush();
    }

    private static void send(JsonNode message) throws Exception {
        System.out.println(MAPPER.writeValueAsString(message));
        System.out.flush();
    }

    private static void reply(JsonNode request, JsonNode result) throws Exception {
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        send(response);
    }
}
