package cn.yhjyhj.weaver.core.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从工具参数 JSON 中提取审批规则需要的结构化字段。 */
public final class ApprovalRules {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // 仅用于读取审批匹配字段

    private ApprovalRules() {}

    /**
     * 读取参数 JSON 顶层的文本型 {@code op}。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @return 操作名；参数缺失、非对象、解析失败或 {@code op} 非文本时为 null
     */
    public static String operationOf(String argumentsJson) {
        return textArgument(argumentsJson, "op");
    }

    /**
     * 读取参数 JSON 顶层的文本字段。
     *
     * @param argumentsJson 模型给出的原始参数 JSON
     * @param field 字段名
     * @return 字段文本；参数或字段不可用时为 null
     */
    static String textArgument(String argumentsJson, String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(argumentsJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode value = root.get(field);
            return value != null && value.isTextual() ? value.textValue() : null;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 审批匹配对畸形模型输出保持宽容，非文本操作按“未知”处理。
            return null;
        }
    }
}
