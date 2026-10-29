package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuleBasedApprovalPolicyTest {

    @Test
    void defaultsAllowGitStatusThroughReversibleFallback() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ALLOW,
                decision(
                        policy,
                        call("git", """
                                {"op":"status"}
                                """),
                        Reversibility.REVERSIBLE));
    }

    @Test
    void defaultsAskForGitCommitAndPush() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("git", """
                                {"op":"commit","message":"change"}
                                """),
                        Reversibility.IRREVERSIBLE));
        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("git", """
                                {"op":"push"}
                                """),
                        Reversibility.IRREVERSIBLE));
    }

    @Test
    void defaultsAskForEveryShellCall() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("shell", """
                                {"cmd":"echo hi"}
                                """),
                        Reversibility.IRREVERSIBLE));
    }

    @Test
    void defaultsAskForFileWriteAndAllowFileRead() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":"src/Main.java","content":"class Main {}"}
                                """),
                        Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.ALLOW,
                decision(
                        policy,
                        call("file", """
                                {"op":"read","path":"src/Main.java"}
                                """),
                        Reversibility.REVERSIBLE));
    }

    @Test
    void hardDenyRejectsWritesToProtectedDirectories() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":".git/config","content":"x"}
                                """),
                        Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":".weaver/state.db","content":"x"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void hardDenyAllowsReadsFromProtectedDirectories() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ALLOW,
                decision(
                        policy,
                        call("file", """
                                {"op":"read","path":".git/config"}
                                """),
                        Reversibility.REVERSIBLE));
    }

    @Test
    void hardDenyRejectsShellDeletionOfProtectedDirectories() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("shell", """
                                {"cmd":"rm -rf .git"}
                                """),
                        Reversibility.IRREVERSIBLE));
        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("shell", """
                                {"cmd":"python -c \\\"shutil.rmtree('.git')\\\""}
                                """),
                        Reversibility.IRREVERSIBLE));
    }

    @Test
    void denyRuleWinsOverAllowRuleRegardlessOfDeclarationOrder() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(
                        new ApprovalRule(null, null, ApprovalDecision.ALLOW),
                        new ApprovalRule("file", "write", ApprovalDecision.DENY)),
                PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":"src/Main.java"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void askRuleWinsOverAllowRuleRegardlessOfDeclarationOrder() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(
                        new ApprovalRule(null, null, ApprovalDecision.ALLOW),
                        new ApprovalRule("file", "write", ApprovalDecision.ASK)),
                PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":"src/Main.java"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void acceptEditsCannotOverrideHardDeny() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule(null, null, ApprovalDecision.ALLOW)),
                PermissionMode.ACCEPT_EDITS);

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":".weaver/state.db"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void planModeDeniesGitCommit() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule("git", "commit", ApprovalDecision.ASK)),
                PermissionMode.PLAN);

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("git", """
                                {"op":"commit","message":"change"}
                                """),
                        Reversibility.IRREVERSIBLE));
    }

    @Test
    void acceptEditsAllowsRecoverableFileWriteAsk() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule("file", "write", ApprovalDecision.ASK)),
                PermissionMode.ACCEPT_EDITS);

        assertEquals(
                ApprovalDecision.ALLOW,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":"src/Main.java"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void firstRuleWithinDecisionLayerWins() {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(
                        new ApprovalRule("file", null, ApprovalDecision.DENY),
                        new ApprovalRule("file", "write", ApprovalDecision.ASK)),
                PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.DENY,
                decision(
                        policy,
                        call("file", """
                                {"op":"write","path":"src/Main.java"}
                                """),
                        Reversibility.RECOVERABLE));
    }

    @Test
    void fallsBackToReversibilityWhenNoRuleMatches() {
        RuleBasedApprovalPolicy policy =
                new RuleBasedApprovalPolicy(List.of(), PermissionMode.DEFAULT);

        assertEquals(
                ApprovalDecision.ALLOW,
                decision(
                        policy,
                        call("custom", "{}"),
                        Reversibility.REVERSIBLE));
        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("custom", "{}"),
                        Reversibility.RECOVERABLE));
        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("custom", "{}"),
                        Reversibility.IRREVERSIBLE));
    }

    @Test
    void malformedArgumentsStillMatchWildcardShellRule() {
        RuleBasedApprovalPolicy policy = RuleBasedApprovalPolicy.defaults();

        assertEquals(
                ApprovalDecision.ASK,
                decision(
                        policy,
                        call("shell", "{"),
                        Reversibility.IRREVERSIBLE));
    }

    @ParameterizedTest(name = "{0}: {1} + {2} -> {3}")
    @CsvSource({
        "DEFAULT, ASK, REVERSIBLE, ASK",
        "DEFAULT, ASK, RECOVERABLE, ASK",
        "DEFAULT, ASK, IRREVERSIBLE, ASK",
        "DEFAULT, ALLOW, RECOVERABLE, ALLOW",
        "DEFAULT, ALLOW, IRREVERSIBLE, ALLOW",
        "DEFAULT, DENY, RECOVERABLE, DENY",
        "PLAN, ASK, REVERSIBLE, ASK",
        "PLAN, ASK, RECOVERABLE, DENY",
        "PLAN, ALLOW, IRREVERSIBLE, DENY",
        "ACCEPT_EDITS, ASK, RECOVERABLE, ALLOW",
        "ACCEPT_EDITS, ASK, IRREVERSIBLE, ASK",
        "ACCEPT_EDITS, ALLOW, IRREVERSIBLE, ALLOW",
        "ACCEPT_EDITS, DENY, RECOVERABLE, DENY"
    })
    void resolvesEveryRuleModeAndReversibilityCombination(
            PermissionMode mode,
            ApprovalDecision baseDecision,
            Reversibility reversibility,
            ApprovalDecision expected) {
        RuleBasedApprovalPolicy policy = new RuleBasedApprovalPolicy(
                List.of(new ApprovalRule("custom", null, baseDecision)), mode);

        assertEquals(
                expected,
                decision(policy, call("custom", "{}"), reversibility));
    }

    private static ApprovalDecision decision(
            RuleBasedApprovalPolicy policy,
            StreamChunk.ToolCall call,
            Reversibility reversibility) {
        return policy.decide(
                        call,
                        reversibility,
                        ApprovalContext.interactive(
                                "session-1", ApprovalGrantStore.none()))
                .decision();
    }

    private static StreamChunk.ToolCall call(
            String toolName, String argumentsJson) {
        return new StreamChunk.ToolCall("call-1", toolName, argumentsJson);
    }
}
