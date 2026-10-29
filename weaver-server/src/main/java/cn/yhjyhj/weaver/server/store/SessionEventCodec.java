package cn.yhjyhj.weaver.server.store;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 在 {@link SessionEvent} 与带类型标签的 JSON 之间转换会话事件。 */
public final class SessionEventCodec {

    /** 事件编解码使用的线程安全 Jackson 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper(); // 读写共享同一配置

    /** 创建使用默认 Jackson 配置的事件编解码器。 */
    public SessionEventCodec() {}

    /**
     * 把会话事件编码为包含 {@code type} 判别字段的 JSON。
     *
     * @param event 待编码事件
     * @return 可持久化的 JSON
     * @throws IllegalArgumentException JSON 编码失败时抛出
     */
    public String encode(SessionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }

        ObjectNode json = objectMapper.valueToTree(event);
        json.put("type", typeOf(event));
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to encode session event", exception);
        }
    }

    /**
     * 按 {@code type} 分派并解析会话事件。
     *
     * <p>未知类型、缺失字段、错误字段类型和非法枚举都会统一抛出
     * {@link IllegalArgumentException}，不会返回 {@code null}。
     *
     * @param json 已持久化的事件 JSON
     * @return 解析后的事件
     * @throws IllegalArgumentException JSON 结构不符合事件契约时抛出
     */
    public SessionEvent decode(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }

        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw invalid("event JSON must be an object");
            }

            ObjectNode root = (ObjectNode) parsed;
            String type = requiredText(root, "type");
            root.remove("type");
            validateFields(type, root);
            return objectMapper.treeToValue(root, eventClass(type));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to decode session event", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        }
    }

    /**
     * 返回事件在持久化 JSON 中使用的类型标签。
     *
     * @param event 会话事件
     * @return 稳定的类型标签
     */
    String typeOf(SessionEvent event) {
        return switch (event) {
            case SessionEvent.MessageAdded ignored -> "MessageAdded";
            case SessionEvent.ToolCallStarted ignored -> "ToolCallStarted";
            case SessionEvent.ToolCallCompleted ignored -> "ToolCallCompleted";
            case SessionEvent.ApprovalRequested ignored -> "ApprovalRequested";
            case SessionEvent.ApprovalResolved ignored -> "ApprovalResolved";
            case SessionEvent.RunFinished ignored -> "RunFinished";
        };
    }

    private Class<? extends SessionEvent> eventClass(String type) {
        return switch (type) {
            case "MessageAdded" -> SessionEvent.MessageAdded.class;
            case "ToolCallStarted" -> SessionEvent.ToolCallStarted.class;
            case "ToolCallCompleted" -> SessionEvent.ToolCallCompleted.class;
            case "ApprovalRequested" -> SessionEvent.ApprovalRequested.class;
            case "ApprovalResolved" -> SessionEvent.ApprovalResolved.class;
            case "RunFinished" -> SessionEvent.RunFinished.class;
            default -> throw invalid("unknown session event type: " + type);
        };
    }

    private void validateFields(String type, ObjectNode payload) {
        switch (type) {
            case "MessageAdded" -> requireFields(payload, "role", "content");
            case "ToolCallStarted" -> requireFields(
                    payload, "callId", "toolName", "argumentsJson");
            case "ToolCallCompleted" -> {
                requireFields(payload, "callId", "result");
                JsonNode result = payload.get("result");
                if (!result.isObject()) {
                    throw invalid("field 'result' must be an object");
                }
                requireFields((ObjectNode) result, "content", "isError", "durationMs");
            }
            case "ApprovalRequested" -> requireFields(
                    payload, "callId", "toolName", "reversibility");
            case "ApprovalResolved" -> requireFields(payload, "callId", "approved", "reason");
            case "RunFinished" -> requireFields(payload, "aborted");
            default -> throw invalid("unknown session event type: " + type);
        }
    }

    private String requiredText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("field '" + field + "' must be a non-blank string");
        }
        return value.textValue();
    }

    private void requireFields(ObjectNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value == null || value.isNull()) {
                throw invalid("missing required field: " + field);
            }
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
