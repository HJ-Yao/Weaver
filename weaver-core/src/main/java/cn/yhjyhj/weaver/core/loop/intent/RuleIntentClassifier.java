package cn.yhjyhj.weaver.core.loop.intent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** M1 关键词规则分类器；存在工具或文件操作信号时优先判为工具意图。 */
public final class RuleIntentClassifier implements IntentClassifier {

    /** 工具和文件操作信号；匹配不区分英文大小写。 */
    private static final List<String> TOOL_KEYWORDS = List.of(
            "read", "write", "run", "git", "file",
            "目录", "文件", "执行", "运行", "修改", "读取", "命令");

    /** 常见命令入口；要求按词边界匹配，避免普通对话中的单词子串误判。 */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?:^|[\\s;&|])(?:mvn|gradle|ls|pwd|cat|grep|find|java|javac|"
                    + "npm|node|python|powershell|cmd|bash|sh)(?:\\s|$)");

    /** 创建无状态规则分类器。 */
    public RuleIntentClassifier() {}

    @Override
    public Intent classify(String input) {
        if (input == null || input.isBlank()) {
            return Intent.CLARIFY;
        }

        String normalized = input.toLowerCase(Locale.ROOT);
        if (normalized.contains("/") || normalized.contains("\\")) {
            return Intent.TOOL_USE;
        }
        if (COMMAND_PATTERN.matcher(normalized).find()) {
            return Intent.TOOL_USE;
        }
        for (String keyword : TOOL_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return Intent.TOOL_USE;
            }
        }
        return Intent.CHAT;
    }
}
