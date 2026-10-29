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
 * 将 Weaver 内部请求转换为 Anthropic Messages JSON。
 *
 * <p>该类只做内部模型到协议 JSON 的转换，不发起网络请求；负责文本、
 * {@code tool_use}、{@code tool_result} 内容块和连续工具结果的合并。
 */
final class AnthropicRequestMapper {

    private static final ObjectMapper JSON = new ObjectMapper(); // 协议 JSON 编解码器

    private final ModelProperties properties; // 模型名称与生成参数

    /**
     * 创建映射器。
     *
     * @param properties 模型配置
     */
    AnthropicRequestMapper(ModelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 把内部模型请求转换为 Messages 请求体。
     *
     * @param request 内部模型输入、历史与工具清单
     * @return Anthropic 兼容的 JSON 请求体
     */
    String requestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", properties.model());
        body.put("max_tokens", properties.maxTokens());
        body.put("temperature", properties.temperature());
        body.put("stream", true);
        body.set("messages", messages(request.history()));
        body.set("tools", tools(request.tools()));
        return body.toString();
    }

    private static ArrayNode messages(List<ModelMessage> history) {
        ArrayNode messages = JSON.createArrayNode();
        ArrayNode adjacentToolResults = null;
        for (ModelMessage message : history) {
            switch (message) {
                case ModelMessage.User user -> {
                    ObjectNode converted = userMessage();
                    converted.putArray("content").add(textBlock(user.content()));
                    messages.add(converted);
                    adjacentToolResults = null;
                }
                case ModelMessage.Assistant assistant -> {
                    ObjectNode converted = assistantMessage(assistant);
                    messages.add(converted);
                    adjacentToolResults = null;
                }
                case ModelMessage.Tool tool -> {
                    if (adjacentToolResults == null) {
                        ObjectNode converted = userMessage();
                        adjacentToolResults = converted.putArray("content");
                        messages.add(converted);
                    }
                    adjacentToolResults.add(toolResultBlock(tool));
                }
            }
        }
        return messages;
    }

    private static ObjectNode assistantMessage(ModelMessage.Assistant assistant) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        ArrayNode content = message.putArray("content");
        if (assistant.content() != null && !assistant.content().isEmpty()) {
            content.add(textBlock(assistant.content()));
        }
        for (StreamChunk.ToolCall call : assistant.toolCalls()) {
            content.add(toolUseBlock(call));
        }
        return message;
    }

    private static ObjectNode userMessage() {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "user");
        return message;
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static ObjectNode toolUseBlock(StreamChunk.ToolCall call) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", call.callId());
        block.put("name", call.toolName());
        block.set("input", jsonObject(
                call.argumentsJson(),
                "tool call arguments must be a JSON object: " + call.toolName()));
        return block;
    }

    private static ObjectNode toolResultBlock(ModelMessage.Tool tool) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", tool.callId());
        block.put("content", tool.content());
        block.put("is_error", tool.isError());
        return block;
    }

    private static ArrayNode tools(List<ToolSpec> specs) {
        ArrayNode tools = JSON.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode converted = JSON.createObjectNode();
            converted.put("name", spec.name());
            converted.put("description", spec.description());
            converted.set("input_schema", jsonObject(
                    spec.parametersSchema(),
                    "tool parameters schema must be a JSON object: " + spec.name()));
            tools.add(converted);
        }
        return tools;
    }

    private static JsonNode jsonObject(String source, String errorMessage) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(source);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(errorMessage, exception);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return parsed;
    }
}
