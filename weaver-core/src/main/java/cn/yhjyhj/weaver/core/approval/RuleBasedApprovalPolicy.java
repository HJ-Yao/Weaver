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
     * @param context 会话交互能力与授权记录
     * @return 最终结论及可审计原因
     */
    @Override
    public ApprovalResult decide(
            StreamChunk.ToolCall call, Reversibility reversibility, ApprovalContext context) {
        requireInputs(call, reversibility, context);

        // 顺序即安全边界：越危险、越不可逆的规则越先裁决。
        ApprovalResult hardDeny = hardDeny(call);
        if (hardDeny != null) {
            return hardDeny;
        }
        ApprovalResult base = baseDecision(call, reversibility);
        base = applyMode(base, reversibility);
        base = applyGrant(base, call, context);
        return applyUnattended(base, context);
    }

    /** 校验裁决所需的非空输入，保持原有参数名作为异常消息。 */
    private static void requireInputs(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            ApprovalContext context) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(reversibility, "reversibility");
        Objects.requireNonNull(context, "context");
    }

    /** 命中不可被规则或会话授权绕过的硬禁区时返回 DENY，否则返回 null。 */
    private static ApprovalResult hardDeny(StreamChunk.ToolCall call) {
        return HARD_DENY_POLICY.isDenied(call)
                ? new ApprovalResult(ApprovalDecision.DENY, "hard-deny")
                : null;
    }

    /** 按 DENY、ASK、ALLOW 分层匹配规则，并在无命中时按回滚性回退。 */
    private ApprovalResult baseDecision(
            StreamChunk.ToolCall call, Reversibility reversibility) {
        String operation = ApprovalRules.operationOf(call.argumentsJson());
        ApprovalDecision decision = firstRule(call, operation, ApprovalDecision.DENY)
                .or(() -> firstRule(call, operation, ApprovalDecision.ASK))
                .or(() -> firstRule(call, operation, ApprovalDecision.ALLOW))
                .map(ApprovalRule::decision)
                .orElseGet(() -> fallbackDecision(reversibility));
        return switch (decision) {
            case ALLOW -> new ApprovalResult(ApprovalDecision.ALLOW, "allowed");
            case ASK -> new ApprovalResult(ApprovalDecision.ASK, "needs-user");
            case DENY -> new ApprovalResult(ApprovalDecision.DENY, "policy");
        };
    }

    /** 应用当前权限模式；PLAN 约束优先于普通 ALLOW，ACCEPT_EDITS 只放宽可恢复的 ASK。 */
    private ApprovalResult applyMode(
            ApprovalResult base, Reversibility reversibility) {
        // 模式修正在授权与无人值守处理前完成，确保会话授权不能绕过 PLAN 约束。
        if (mode == PermissionMode.PLAN && reversibility != Reversibility.REVERSIBLE) {
            return new ApprovalResult(ApprovalDecision.DENY, "policy");
        }
        if (mode == PermissionMode.ACCEPT_EDITS
                && base.decision() == ApprovalDecision.ASK
                && reversibility == Reversibility.RECOVERABLE) {
            return new ApprovalResult(ApprovalDecision.ALLOW, "allowed");
        }
        return base;
    }

    /** 当前会话已明确授权同一工具操作时，将 ASK 提升为 ALLOW。 */
    private static ApprovalResult applyGrant(
            ApprovalResult base,
            StreamChunk.ToolCall call,
            ApprovalContext context) {
        if (base.decision() != ApprovalDecision.ASK) {
            return base;
        }
        String operation = ApprovalRules.operationOf(call.argumentsJson());
        // 已授权操作先于无人值守降级，使同一会话中明确批准过的操作仍可继续。
        String grantOperation = operation == null ? "*" : operation;
        if (context.grants().isGranted(
                context.sessionId(), call.toolName(), grantOperation)) {
            return new ApprovalResult(
                    ApprovalDecision.ALLOW, "session-granted");
        }
        return base;
    }

    /** 非交互上下文中无法询问用户的 ASK 降级为 DENY。 */
    private static ApprovalResult applyUnattended(
            ApprovalResult base, ApprovalContext context) {
        if (base.decision() == ApprovalDecision.ASK && !context.interactive()) {
            return new ApprovalResult(ApprovalDecision.DENY, "unattended");
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
