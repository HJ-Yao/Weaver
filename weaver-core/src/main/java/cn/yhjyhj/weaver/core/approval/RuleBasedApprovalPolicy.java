package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 按硬禁区、决策分层和权限模式组成的固定顺序裁决工具调用。 */
public final class RuleBasedApprovalPolicy implements ApprovalPolicy {

    private static final List<ApprovalRule> DEFAULT_RULES = List.of(
            new ApprovalRule("file", "write", ApprovalDecision.ASK),
            new ApprovalRule("git", "commit", ApprovalDecision.ASK),
            new ApprovalRule("git", "push", ApprovalDecision.ASK),
            new ApprovalRule("shell", null, ApprovalDecision.ASK)); // shell 参数没有 op，匹配全部 shell
    private static final HardDenyPolicy HARD_DENY_POLICY = new HardDenyPolicy(); // 无状态终局禁区

    private final List<ApprovalRule> rules; // 保持调用方声明顺序的不可变规则快照
    private final PermissionMode mode; // 当前会话的权限模式

    /**
     * 创建规则驱动的审批策略。
     *
     * @param rules 审批规则；同层内按声明顺序匹配
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

    /**
     * 按固定优先级裁决工具调用。
     *
     * <p>先执行不可被规则绕过的硬禁区，再按 DENY、ASK、ALLOW 分层匹配规则；无规则命中时
     * 根据回滚性回退，最后应用 PLAN 与 ACCEPT_EDITS 模式修正。
     *
     * @param call 待审批的工具调用
     * @param reversibility 工具声明的回滚性
     * @return 最终审批决策
     */
    @Override
    public ApprovalDecision decide(StreamChunk.ToolCall call, Reversibility reversibility) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(reversibility, "reversibility");

        if (HARD_DENY_POLICY.isDenied(call)) {
            return ApprovalDecision.DENY;
        }

        String operation = ApprovalRules.operationOf(call.argumentsJson());
        ApprovalDecision base = firstRule(call, operation, ApprovalDecision.DENY)
                .or(() -> firstRule(call, operation, ApprovalDecision.ASK))
                .or(() -> firstRule(call, operation, ApprovalDecision.ALLOW))
                .map(ApprovalRule::decision)
                .orElseGet(() -> fallbackDecision(reversibility));

        // PLAN 的模式约束覆盖基础裁决，确保非只读调用不能借 ALLOW 规则绕过。
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

    private Optional<ApprovalRule> firstRule(
            StreamChunk.ToolCall call,
            String operation,
            ApprovalDecision decision) {
        return rules.stream()
                .filter(rule -> rule.decision() == decision)
                .filter(rule -> matches(rule, call.toolName(), operation))
                .findFirst();
    }

    private static boolean matches(
            ApprovalRule rule, String toolName, String operation) {
        boolean toolMatches = rule.toolName() == null || rule.toolName().equals(toolName);
        boolean operationMatches =
                rule.operation() == null || rule.operation().equals(operation);
        return toolMatches && operationMatches;
    }

    private static ApprovalDecision fallbackDecision(Reversibility reversibility) {
        return reversibility == Reversibility.REVERSIBLE
                ? ApprovalDecision.ALLOW
                : ApprovalDecision.ASK;
    }
}
