package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedApprovalPolicyTest {

    @Test
    void defaultsMatchGitReadCommandsAsAllow() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("git", "status --short"), Reversibility.REVERSIBLE));
        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("git", "diff HEAD"), Reversibility.REVERSIBLE));
    }

    @Test
    void defaultsMatchGitWriteCommandsAsAsk() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("git", "push"), Reversibility.IRREVERSIBLE));
        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("git", "commit -m message"), Reversibility.IRREVERSIBLE));
    }

    @Test
    void defaultsDenyShellDeletionCommands() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.DENY,
                policy.decide(call("shell", "rm -rf build"), Reversibility.IRREVERSIBLE));
    }

    @Test
    void defaultsAskForShellDependencyInstallation() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("shell", "npm install package"), Reversibility.IRREVERSIBLE));
    }

    @Test
    void matchesFirstRuleInDeclarationOrder() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(
                        new ApprovalRule("git", ".*", ApprovalDecision.DENY),
                        new ApprovalRule("git", "status", ApprovalDecision.ALLOW)),
                PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.DENY,
                policy.decide(call("git", "status"), Reversibility.REVERSIBLE));
    }

    @Test
    void fallsBackToReversibilityWhenNoRuleMatches() {
        RuleBasedApprovalPolicy policy =
                new RuleBasedApprovalPolicy(List.of(), PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("custom", "{}"), Reversibility.REVERSIBLE));
        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("custom", "{}"), Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("custom", "{}"), Reversibility.IRREVERSIBLE));
    }

    @Test
    void allowsReversibleAskAfterReversibilityCorrection() {
        RuleBasedApprovalPolicy policy = policyWith(ApprovalDecision.ASK);

        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("custom", "{}"), Reversibility.REVERSIBLE));
    }

    @Test
    void asksBeforeAllowingIrreversibleCall() {
        RuleBasedApprovalPolicy policy = policyWith(ApprovalDecision.ALLOW);

        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("custom", "{}"), Reversibility.IRREVERSIBLE));
    }

    @Test
    void planModeDeniesEveryNonReversibleCallAfterRuleResolution() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule("custom", ".*", ApprovalDecision.ALLOW)),
                PermissionMode.PLAN);

        assertEquals(
                ApprovalDecision.DENY,
                policy.decide(call("custom", "{}"), Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.DENY,
                policy.decide(call("custom", "{}"), Reversibility.IRREVERSIBLE));
        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("custom", "{}"), Reversibility.REVERSIBLE));
    }

    @Test
    void acceptEditsAllowsOnlyRecoverableAskCalls() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(), PermissionMode.ACCEPT_EDITS);

        assertEquals(
                ApprovalDecision.ALLOW,
                policy.decide(call("edit", "{}"), Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.ASK,
                policy.decide(call("push", "{}"), Reversibility.IRREVERSIBLE));
    }

    private static RuleBasedApprovalPolicy policyWith(ApprovalDecision decision) {
        return new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule("custom", ".*", decision)),
                PermissionMode.DEFAULT);
    }

    private static StreamChunk.ToolCall call(String toolName, String argumentsJson) {
        return new StreamChunk.ToolCall("call-1", toolName, argumentsJson);
    }
}
