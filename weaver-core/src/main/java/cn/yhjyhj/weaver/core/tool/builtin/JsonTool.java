package cn.yhjyhj.weaver.core.tool.builtin;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** 将 JSON 参数解析、失败转换与动态声明统一封装的工具基类。 */
abstract class JsonTool implements Tool {

    private final ObjectMapper objectMapper; // 解析模型参数 JSON 的唯一入口

    /** 创建使用默认 Jackson 配置的工具。 */
    JsonTool() {
        this(new ObjectMapper());
    }

    /**
     * 使用指定 ObjectMapper 创建工具。
     *
     * @param objectMapper JSON 解析器
     */
    JsonTool(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** {@inheritDoc} */
    @Override
    public final ToolResult execute(String argumentsJson) {
        try {
            return executeArguments(arguments(argumentsJson));
        } catch (IllegalArgumentException exception) {
            return new ToolResult("invalid arguments: " + messageOf(exception), true, 0L);
        } catch (Exception exception) {
            return new ToolResult("tool failed: " + messageOf(exception), true, 0L);
        }
    }

    /** {@inheritDoc} */
    @Override
    public final Reversibility reversibility(String argumentsJson) {
        try {
            return declaredReversibility(arguments(argumentsJson));
        } catch (IllegalArgumentException exception) {
            return Reversibility.IRREVERSIBLE;
        }
    }

    /** {@inheritDoc} */
    @Override
    public final boolean parallelizable(String argumentsJson) {
        try {
            return declaredParallelizable(arguments(argumentsJson));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public final Reversibility reversibility() {
        return Reversibility.IRREVERSIBLE;
    }

    /** {@inheritDoc} */
    @Override
    public final boolean parallelizable() {
        return false;
    }

    /**
     * 执行已解析的工具参数。
     *
     * @param arguments 已确认是 JSON 对象的参数
     * @return 工具执行结果
     * @throws Exception 文件或进程操作失败时抛出
     */
    protected abstract ToolResult executeArguments(ObjectNode arguments) throws Exception;

    /**
     * 根据已解析参数声明回滚性。
     *
     * @param arguments 已确认是 JSON 对象的参数
     * @return 本次调用的回滚性
     */
    protected abstract Reversibility declaredReversibility(ObjectNode arguments);

    /**
     * 根据已解析参数声明并行性。
     *
     * @param arguments 已确认是 JSON 对象的参数
     * @return 本次调用是否可并行
     */
    protected abstract boolean declaredParallelizable(ObjectNode arguments);

    /**
     * 解析并校验参数是 JSON 对象。
     *
     * @param argumentsJson 原始参数 JSON
     * @return JSON 对象
     */
    protected final ObjectNode arguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("arguments JSON must not be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            if (!(root instanceof ObjectNode objectNode)) {
                throw new IllegalArgumentException("arguments JSON must be an object");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(messageOf(exception), exception);
        }
    }

    /**
     * 读取必填文本字段。
     *
     * @param arguments JSON 参数
     * @param field 字段名
     * @return 字段文本
     */
    protected static String requiredText(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("missing text field: " + field);
        }
        return value.textValue();
    }

    /**
     * 读取必填的非空白文本字段。
     *
     * @param arguments JSON 参数
     * @param field 字段名
     * @return 非空白文本
     */
    protected static String requiredNonBlankText(ObjectNode arguments, String field) {
        String value = requiredText(arguments, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("field must not be blank: " + field);
        }
        return value;
    }

    /**
     * 读取可选文本字段。
     *
     * @param arguments JSON 参数
     * @param field 字段名
     * @return 字段文本；字段不存在时为 null
     */
    protected static String optionalText(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("field must be text: " + field);
        }
        return value.textValue();
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
