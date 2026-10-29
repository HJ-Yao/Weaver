package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import org.junit.jupiter.api.Test;

class ApprovalPolicyContextTest {

    private static final String SESSION_ID = "session-1"; // 固定当前测试会话

    @Test
    void unattendedAskBecomesDenyWithUnattendedReason() {
        ApprovalResult result = decide(
                call("git", """
                        {"op":"commit","message":"change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.unattended(
                        SESSION_ID, ApprovalGrantStore.none()));

        assertEquals(new ApprovalResult(ApprovalDecision.DENY, "unattended"), result);
    }

    @Test
    void interactiveAskWaitsForUserWithNeedsUserReason() {
        ApprovalResult result = decide(
                call("git", """
                        {"op":"commit","message":"change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive(
                        SESSION_ID, ApprovalGrantStore.none()));

        assertEquals(new ApprovalResult(ApprovalDecision.ASK, "needs-user"), result);
    }

    @Test
    void sessionGrantAllowsSameOperationButStillAsksForAnotherOperation() {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        grants.grant(SESSION_ID, "git", "commit");

        ApprovalResult commit = decide(
                call("git", """
                        {"op":"commit","message":"change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive(SESSION_ID, grants));
        ApprovalResult push = decide(
                call("git", """
                        {"op":"push"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive(SESSION_ID, grants));

        assertEquals(
                new ApprovalResult(ApprovalDecision.ALLOW, "session-granted"),
                commit);
        assertEquals(new ApprovalResult(ApprovalDecision.ASK, "needs-user"), push);
    }

    @Test
    void grantIsScopedToSessionToolAndOperation() {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        grants.grant(SESSION_ID, "git", "commit");

        ApprovalResult otherSession = decide(
                call("git", """
                        {"op":"commit","message":"change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive("session-2", grants));
        ApprovalResult otherTool = decide(
                call("shell", """
                        {"op":"commit","cmd":"echo change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive(SESSION_ID, grants));

        assertEquals(
                new ApprovalResult(ApprovalDecision.ASK, "needs-user"),
                otherSession);
        assertEquals(
                new ApprovalResult(ApprovalDecision.ASK, "needs-user"),
                otherTool);
    }

    @Test
    void sessionGrantStillAllowsWhenUnattended() {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        grants.grant(SESSION_ID, "git", "commit");

        ApprovalResult result = decide(
                call("git", """
                        {"op":"commit","message":"change"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.unattended(SESSION_ID, grants));

        assertEquals(
                new ApprovalResult(ApprovalDecision.ALLOW, "session-granted"),
                result);
    }

    @Test
    void hardDenyWinsOverSessionGrant() {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        grants.grant(SESSION_ID, "file", "write");

        ApprovalResult result = decide(
                call("file", """
                        {"op":"write","path":".git/config","content":"x"}
                        """),
                Reversibility.RECOVERABLE,
                ApprovalContext.interactive(SESSION_ID, grants));

        assertEquals(new ApprovalResult(ApprovalDecision.DENY, "hard-deny"), result);
    }

    @Test
    void operationlessGrantUsesWildcardGranularity() {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        grants.grant(SESSION_ID, "shell", "*");

        ApprovalResult result = decide(
                call("shell", """
                        {"cmd":"echo hi"}
                        """),
                Reversibility.IRREVERSIBLE,
                ApprovalContext.interactive(SESSION_ID, grants));

        assertEquals(
                new ApprovalResult(ApprovalDecision.ALLOW, "session-granted"),
                result);
    }

    @Test
    void reversibleFallbackReportsAllowedReason() {
        ApprovalResult result = decide(
                call("git", """
                        {"op":"status"}
                        """),
                Reversibility.REVERSIBLE,
                ApprovalContext.unattended(
                        SESSION_ID, ApprovalGrantStore.none()));

        assertEquals(new ApprovalResult(ApprovalDecision.ALLOW, "allowed"), result);
    }

    @Test
    void emptyGrantStoreNeverReportsGranted() {
        ApprovalGrantStore grants = ApprovalGrantStore.none();
        grants.grant(SESSION_ID, "git", "commit");

        assertFalse(grants.isGranted(SESSION_ID, "git", "commit"));
    }

    @Test
    void approvalResultRequiresAuditableReason() {
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalResult(ApprovalDecision.DENY, null));
    }

    private static ApprovalResult decide(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            ApprovalContext context) {
        return RuleBasedApprovalPolicy.defaults().decide(
                call, reversibility, context);
    }

    private static StreamChunk.ToolCall call(String toolName, String argumentsJson) {
        return new StreamChunk.ToolCall("call-1", toolName, argumentsJson);
    }
}
