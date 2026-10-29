package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalResult;
import cn.yhjyhj.weaver.core.approval.InMemoryApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.node.ActNode;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApprovalAuditTest {

    private static final String SESSION_ID = "session-1"; // 固定当前测试会话

    @Test
    void emitsResolvedAuditFactForAllowAskAndDeny() throws Exception {
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalGate interactiveGate = new ApprovalGate() {
            @Override
            public boolean requestApproval(
                    StreamChunk.ToolCall call, Reversibility reversibility) {
                gateCalls.incrementAndGet();
                return true;
            }
        };
        List<SessionEvent> emitted = execute(
                RuleBasedApprovalPolicy.defaults(),
                interactiveGate,
                ApprovalGrantStore.none(),
                call("call-allow", "git", """
                        {"op":"status"}
                        """),
                call("call-ask", "git", """
                        {"op":"commit","message":"change"}
                        """),
                call("call-deny", "file", """
                        {"op":"write","path":".git/config","content":"x"}
                        """));

        assertEquals(1, gateCalls.get());
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalResolved(
                                "call-allow", true, "allowed"),
                        new SessionEvent.ApprovalRequested(
                                "call-ask", "git", Reversibility.IRREVERSIBLE),
                        new SessionEvent.ApprovalResolved(
                                "call-ask", true, "user"),
                        new SessionEvent.ApprovalRequested(
                                "call-deny", "file", Reversibility.RECOVERABLE),
                        new SessionEvent.ApprovalResolved(
                                "call-deny", false, "hard-deny")),
                emitted.stream()
                        .filter(event -> event instanceof SessionEvent.ApprovalRequested
                                || event instanceof SessionEvent.ApprovalResolved)
                        .toList());
    }

    @Test
    void approvedAskGrantsSameToolAndOperationForLaterCalls() throws Exception {
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalGate interactiveGate = new ApprovalGate() {
            @Override
            public boolean requestApproval(
                    StreamChunk.ToolCall call, Reversibility reversibility) {
                gateCalls.incrementAndGet();
                return true;
            }
        };
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();

        List<SessionEvent> emitted = execute(
                RuleBasedApprovalPolicy.defaults(),
                interactiveGate,
                grants,
                call("call-1", "git", """
                        {"op":"commit","message":"first"}
                        """),
                call("call-2", "git", """
                        {"op":"commit","message":"second"}
                        """));

        assertEquals(1, gateCalls.get());
        assertTrue(grants.isGranted(SESSION_ID, "git", "commit"));
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalResolved("call-1", true, "user"),
                        new SessionEvent.ApprovalResolved(
                                "call-2", true, "session-granted")),
                emitted.stream()
                        .filter(SessionEvent.ApprovalResolved.class::isInstance)
                        .toList());
    }

    @Test
    void unattendedAskIsDeniedAndReportedInToolResult() throws Exception {
        List<SessionEvent> emitted = execute(
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none(),
                call("call-1", "git", """
                        {"op":"commit","message":"change"}
                        """));

        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "git", Reversibility.IRREVERSIBLE),
                        new SessionEvent.ApprovalResolved(
                                "call-1", false, "unattended"),
                        new SessionEvent.ToolCallStarted(
                                "call-1", "git", """
                                        {"op":"commit","message":"change"}
                                        """),
                        new SessionEvent.ToolCallCompleted(
                                "call-1",
                                new ToolResult("denied by unattended", true, 0L))),
                emitted);
    }

    @Test
    void preservingResultsUsesApprovalResultReason() throws Exception {
        List<SessionEvent> emitted = execute(
                (call, reversibility, context) -> new ApprovalResult(
                        ApprovalDecision.DENY, "policy-one-off"),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none(),
                call("call-1", "shell", "{}"));

        SessionEvent.ToolCallCompleted completed = emitted.stream()
                .filter(SessionEvent.ToolCallCompleted.class::isInstance)
                .map(SessionEvent.ToolCallCompleted.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("denied by policy-one-off", completed.result().content());
    }

    @Test
    void rejectedUserApprovalDoesNotCreateSessionGrant() throws Exception {
        ApprovalGrantStore grants = new InMemoryApprovalGrantStore();

        execute(
                RuleBasedApprovalPolicy.defaults(),
                (call, reversibility) -> false,
                grants,
                call("call-1", "git", """
                        {"op":"commit","message":"change"}
                        """));

        assertFalse(grants.isGranted(SESSION_ID, "git", "commit"));
    }

    private static List<SessionEvent> execute(
            cn.yhjyhj.weaver.core.approval.ApprovalPolicy policy,
            ApprovalGate gate,
            ApprovalGrantStore grants,
            StreamChunk.ToolCall... calls) throws Exception {
        List<SessionEvent> emitted = new ArrayList<>();
        RunContext context = new RunContext(SESSION_ID, emitted::add);
        context.setPendingToolCalls(List.of(calls));
        ActNode node = new ActNode(
                new ToolRegistry(List.of(tool("git"), tool("file"), tool("shell"))),
                (request, ignored) -> null,
                policy,
                gate,
                grants);

        assertNull(node.apply(
                new RunRequest(SESSION_ID, "input", 0), context));
        return emitted;
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Reversibility reversibility(String argumentsJson) {
                if (name.equals("git") && argumentsJson.contains("\"status\"")) {
                    return Reversibility.REVERSIBLE;
                }
                if (name.equals("file") && argumentsJson.contains("\"read\"")) {
                    return Reversibility.REVERSIBLE;
                }
                return name.equals("git")
                        ? Reversibility.IRREVERSIBLE
                        : Reversibility.RECOVERABLE;
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("executed", false, 0L);
            }
        };
    }

    private static StreamChunk.ToolCall call(
            String callId, String toolName, String argumentsJson) {
        return new StreamChunk.ToolCall(callId, toolName, argumentsJson);
    }
}
