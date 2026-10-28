package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 按声明顺序匹配规则，并用回滚性与权限模式修正结果。 */
public final class RuleBasedApprovalPolicy implements ApprovalPolicy {

    private static final List<ApprovalRule> DEFAULT_RULES = List.of(
            new ApprovalRule("git", "^(status|diff|log)\\b.*", ApprovalDecision.ALLOW),
            new ApprovalRule("git", "^(commit|push)\\b.*", ApprovalDecision.ASK),
            new ApprovalRule(
                    "shell",
                    "(?i).*\\b(rm|del|rmdir|format)\\b.*",
                    ApprovalDecision.DENY),
            new ApprovalRule(
                    "shell",
                    "(?i).*\\b(npm|pnpm|yarn|mvn|gradle)\\b.*(install|add)\\b.*",
                    ApprovalDecision.ASK));

    private final List<ApprovalRule> rules; // 保持调用方声明顺序的不可变规则快照
    private final PermissionMode mode; // 当前会话的权限模式

    /**
     * 创建规则驱动审批策略。
     *
     * @param rules 按优先顺序匹配的审批规则
     * @param mode 当前会话的权限模式
     */
    public RuleBasedApprovalPolicy(List<ApprovalRule> rules, PermissionMode mode) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * 使用内置安全规则和 DEFAULT 模式创建策略。
     *
     * @return 默认审批策略
     */
    public static RuleBasedApprovalPolicy defaults() {
        return new RuleBasedApprovalPolicy(DEFAULT_RULES, PermissionMode.DEFAULT);
    }

    @Override
    public ApprovalDecision decide(
            StreamChunk.ToolCall call, Reversibility reversibility) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(reversibility, "reversibility");

        ApprovalDecision base = findRule(call)
                .map(ApprovalRule::decision)
                .orElseGet(() -> fallbackDecision(reversibility));

        // 无副作用的调用无需打断用户；不可回滚调用不能被规则静默放行。
        if (base == ApprovalDecision.ASK && reversibility == Reversibility.REVERSIBLE) {
            base = ApprovalDecision.ALLOW;
        }
        if (base == ApprovalDecision.ALLOW
                && reversibility == Reversibility.IRREVERSIBLE) {
            base = ApprovalDecision.ASK;
        }

        // 模式修正最后执行，确保 PLAN 的只读约束不会被其他规则绕过。
        if (mode == PermissionMode.PLAN && reversibility != Reversibility.REVERSIBLE) {
            return ApprovalDecision.DENY;
        }
        if (mode == PermissionMode.ACCEPT_EDITS
                && base == ApprovalDecision.ASK
                && reversibility == Reversibility.RECOVERABLE) {
            return ApprovalDecision.ALLOW;
        }
        return base;
    }

    private java.util.Optional<ApprovalRule> findRule(StreamChunk.ToolCall call) {
        return rules.stream()
                .filter(rule -> Pattern.matches(rule.toolPattern(), call.toolName()))
                .filter(rule -> Pattern.matches(rule.argsPattern(), call.argumentsJson()))
                .findFirst();
    }

    private static ApprovalDecision fallbackDecision(Reversibility reversibility) {
        return reversibility == Reversibility.REVERSIBLE
                ? ApprovalDecision.ALLOW
                : ApprovalDecision.ASK;
    }
}
