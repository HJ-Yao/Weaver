package cn.yhjyhj.weaver.core.loop.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalResult;
import cn.yhjyhj.weaver.core.approval.InMemoryApprovalGrantStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolApprovalCoordinatorTest {

    private static final String SESSION_ID = "session"; // 固定测试会话

    @Test
    void allowOnlyRecordsResolvedWithoutRequestingApproval() {
        List<SessionEvent> emitted = new ArrayList<>();
        ToolApprovalCoordinator coordinator = coordinator(
                (call, reversibility, context) ->
                        new ApprovalResult(ApprovalDecision.ALLOW, "allowed"),
                (call, reversibility) -> true,
                ApprovalGrantStore.none());

        List<ApprovalOutcome> outcomes = coordinator.approveAll(
                List.of(call("call-1", "file")),
                List.of(tool("file", Reversibility.REVERSIBLE)),
                context(emitted));

        assertTrue(outcomes.getFirst().approved());
        assertNull(outcomes.getFirst().reason());
        assertEquals(
                List.of(new SessionEvent.ApprovalResolved(
                        "call-1", true, "allowed")),
                emitted);
    }

    @Test
    void denyRecordsRequestAndResolvedWithReason() {
        List<SessionEvent> emitted = new ArrayList<>();
        ToolApprovalCoordinator coordinator = coordinator(
                (call, reversibility, context) ->
                        new ApprovalResult(ApprovalDecision.DENY, "blocked"),
                (call, reversibility) -> true,
                ApprovalGrantStore.none());

        List<ApprovalOutcome> outcomes = coordinator.approveAll(
                List.of(call("call-1", "file")),
                List.of(tool("file", Reversibility.RECOVERABLE)),
                context(emitted));

        assertFalse(outcomes.getFirst().approved());
        assertEquals("blocked", outcomes.getFirst().reason());
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "file", Reversibility.RECOVERABLE),
                        new SessionEvent.ApprovalResolved(
                                "call-1", false, "blocked")),
                emitted);
    }

    @Test
    void approvedAskGrantsOperationAndRecordsUserReason() {
        List<SessionEvent> emitted = new ArrayList<>();
        InMemoryApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        ToolApprovalCoordinator coordinator = coordinator(
                (call, reversibility, context) ->
                        new ApprovalResult(ApprovalDecision.ASK, "needs-user"),
                (call, reversibility) -> true,
                grants);

        List<ApprovalOutcome> outcomes = coordinator.approveAll(
                List.of(call("call-1", "file")),
                List.of(tool("file", Reversibility.RECOVERABLE)),
                context(emitted));

        assertTrue(outcomes.getFirst().approved());
        assertTrue(grants.isGranted(SESSION_ID, "file", "write"));
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "file", Reversibility.RECOVERABLE),
                        new SessionEvent.ApprovalResolved(
                                "call-1", true, "user")),
                emitted);
    }

    @Test
    void rejectedAskRecordsUserDeniedAndDoesNotGrant() {
        List<SessionEvent> emitted = new ArrayList<>();
        InMemoryApprovalGrantStore grants = new InMemoryApprovalGrantStore();
        ToolApprovalCoordinator coordinator = coordinator(
                (call, reversibility, context) ->
                        new ApprovalResult(ApprovalDecision.ASK, "needs-user"),
                (call, reversibility) -> false,
                grants);

        List<ApprovalOutcome> outcomes = coordinator.approveAll(
                List.of(call("call-1", "file")),
                List.of(tool("file", Reversibility.RECOVERABLE)),
                context(emitted));

        assertFalse(outcomes.getFirst().approved());
        assertEquals("user-denied", outcomes.getFirst().reason());
        assertFalse(grants.isGranted(SESSION_ID, "file", "write"));
        assertEquals(
                new SessionEvent.ApprovalResolved(
                        "call-1", false, "user-denied"),
                emitted.getLast());
    }

    @Test
    void unknownToolIsDecidedAsIrreversible() {
        List<SessionEvent> emitted = new ArrayList<>();
        AtomicReference<Reversibility> observed = new AtomicReference<>();
        ToolApprovalCoordinator coordinator = coordinator(
                (call, reversibility, context) -> {
                    observed.set(reversibility);
                    return new ApprovalResult(ApprovalDecision.ALLOW, "allowed");
                },
                (call, reversibility) -> true,
                ApprovalGrantStore.none());

        coordinator.approveAll(
                List.of(call("call-1", "missing")),
                Collections.singletonList(null),
                context(emitted));

        assertEquals(Reversibility.IRREVERSIBLE, observed.get());
    }

    private static ToolApprovalCoordinator coordinator(
            cn.yhjyhj.weaver.core.approval.ApprovalPolicy policy,
            ApprovalGate gate,
            ApprovalGrantStore grants) {
        return new ToolApprovalCoordinator(policy, gate, grants);
    }

    private static RunContext context(List<SessionEvent> emitted) {
        return new RunContext(SESSION_ID, emitted::add);
    }

    private static StreamChunk.ToolCall call(String callId, String toolName) {
        return new StreamChunk.ToolCall(callId, toolName, "{\"op\":\"write\"}");
    }

    private static Tool tool(String name, Reversibility reversibility) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("executed", false, 0L);
            }

            @Override
            public Reversibility reversibility(String argumentsJson) {
                return reversibility;
            }
        };
    }
}
