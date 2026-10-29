package cn.yhjyhj.weaver.core.loop.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {

    @Test
    void deniedCallDoesNotExecute() {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = tool("write", true, argumentsJson -> {
            executions.incrementAndGet();
            return new ToolResult("executed", false, 0L);
        });
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(tool)));

        List<ToolResult> results = executor.execute(
                List.of(call("call-1", "write")),
                List.of(tool),
                List.of(ApprovalOutcome.deny("policy")));

        assertEquals(
                List.of(new ToolResult("denied by policy", true, 0L)),
                results);
        assertEquals(0, executions.get());
    }

    @Test
    void executesAllParallelizableToolsConcurrentlyInCallOrder() {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        Tool first = tool("first", true, argumentsJson -> {
            await(secondCompleted);
            return new ToolResult("first-result", false, 0L);
        });
        Tool second = tool("second", true, argumentsJson -> {
            secondCompleted.countDown();
            return new ToolResult("second-result", false, 0L);
        });
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(first, second)));

        List<ToolResult> results = executor.execute(
                List.of(call("call-1", "first"), call("call-2", "second")),
                List.of(first, second),
                List.of(ApprovalOutcome.allow(), ApprovalOutcome.allow()));

        assertEquals(
                List.of("first-result", "second-result"),
                results.stream().map(ToolResult::content).toList());
    }

    @Test
    void runsSeriallyWhenAnyToolIsNotParallelizable() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Tool first = activeTool("first", true, active, maxActive);
        Tool second = activeTool("second", false, active, maxActive);
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(first, second)));

        executor.execute(
                List.of(call("call-1", "first"), call("call-2", "second")),
                List.of(first, second),
                List.of(ApprovalOutcome.allow(), ApprovalOutcome.allow()));

        assertEquals(1, maxActive.get());
    }

    @Test
    void convertsUnknownNullAndThrownFailuresIntoOrderedErrors() {
        Tool returnsNull = tool("null", true, argumentsJson -> null);
        Tool throwsFailure = tool("throws", true, argumentsJson -> {
            throw new IllegalStateException("boom");
        });
        ToolExecutor executor =
                new ToolExecutor(new ToolRegistry(List.of(returnsNull, throwsFailure)));

        List<ToolResult> results = executor.execute(
                List.of(
                        call("call-1", "null"),
                        call("call-2", "throws"),
                        call("call-3", "missing")),
                Arrays.asList(returnsNull, throwsFailure, null),
                List.of(
                        ApprovalOutcome.allow(),
                        ApprovalOutcome.allow(),
                        ApprovalOutcome.allow()));

        assertEquals(
                List.of(
                        "tool returned null: null",
                        "tool execution failed: boom",
                        "unknown tool: missing"),
                results.stream().map(ToolResult::content).toList());
        assertTrue(results.stream().allMatch(ToolResult::isError));
    }

    private static Tool activeTool(
            String name,
            boolean parallelizable,
            AtomicInteger active,
            AtomicInteger maxActive) {
        return tool(name, parallelizable, argumentsJson -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            pause();
            active.decrementAndGet();
            return new ToolResult(name, false, 0L);
        });
    }

    private static Tool tool(
            String name,
            boolean parallelizable,
            Function<String, ToolResult> behavior) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return behavior.apply(argumentsJson);
            }

            @Override
            public boolean parallelizable() {
                return parallelizable;
            }
        };
    }

    private static StreamChunk.ToolCall call(String callId, String toolName) {
        return new StreamChunk.ToolCall(callId, toolName, "{}");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tools did not run concurrently");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", exception);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(25L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", exception);
        }
    }
}
