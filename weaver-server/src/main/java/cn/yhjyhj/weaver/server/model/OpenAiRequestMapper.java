package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.ToolSpec;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/**
 * 将 Weaver 内部请求转换为 OpenAI Chat Completions JSON。
 *
 * <p>该类只做内部模型到协议 JSON 的转换，不发起网络请求；消息、工具和
 * JSON Schema 的格式与校验错误均在此边界内处理。
 */
final class OpenAiRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper(); // 协议 JSON 编解码器

    private final ModelProperties properties; // 模型名称与生成参数

    /**
     * 创建映射器。
     *
     * @param properties 模型配置
     */
    OpenAiRequestMapper(ModelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 把内部模型请求转换为 Chat Completions 请求体。
     *
     * @param request 内部模型输入、历史与工具清单
     * @return OpenAI 兼容的 JSON 请求体
     */
    String requestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", properties.model());
        body.put("stream", true);
        body.put("max_tokens", properties.maxTokens());
        body.put("temperature", properties.temperature());
        body.set("messages", messages(request));
        body.set("tools", tools(request.tools()));
        return body.toString();
    }

    private static ArrayNode messages(ModelRequest request) {
        ArrayNode messages = JSON.createArrayNode();
        for (ModelMessage message : request.history()) {
            ObjectNode converted = JSON.createObjectNode();
            switch (message) {
                case ModelMessage.User user -> {
                    converted.put("role", "user");
                    converted.put("content", user.content());
                }
                case ModelMessage.Assistant assistant -> {
                    converted.put("role", "assistant");
                    converted.put("content", assistant.content());
                    if (!assistant.toolCalls().isEmpty()) {
                        converted.set("tool_calls", toolCalls(assistant.toolCalls()));
                    }
                }
                case ModelMessage.Tool tool -> {
                    converted.put("role", "tool");
                    converted.put("tool_call_id", tool.callId());
                    converted.put("content", tool.content());
                }
            }
            messages.add(converted);
        }
        return messages;
    }

    private static ArrayNode toolCalls(List<StreamChunk.ToolCall> calls) {
        ArrayNode toolCalls = JSON.createArrayNode();
        for (StreamChunk.ToolCall call : calls) {
            ObjectNode convertedCall = JSON.createObjectNode();
            convertedCall.put("id", call.callId());
            convertedCall.put("type", "function");
            ObjectNode function = JSON.createObjectNode();
            function.put("name", call.toolName());
            function.put("arguments", call.argumentsJson());
            convertedCall.set("function", function);
            toolCalls.add(convertedCall);
        }
        return toolCalls;
    }

    private static ArrayNode tools(List<ToolSpec> specs) {
        ArrayNode tools = JSON.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode converted = JSON.createObjectNode();
            converted.put("type", "function");
            ObjectNode function = JSON.createObjectNode();
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.set("parameters", toolParameters(spec));
            converted.set("function", function);
            tools.add(converted);
        }
        return tools;
    }

    private static JsonNode toolParameters(ToolSpec spec) {
        JsonNode parameters;
        try {
            parameters = JSON.readTree(spec.parametersSchema());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "invalid tool parameters schema: " + spec.name(), exception);
        }
        if (parameters == null || !parameters.isObject()) {
            throw new IllegalArgumentException(
                    "tool parameters schema must be a JSON object: " + spec.name());
        }
        return parameters;
    }
}
