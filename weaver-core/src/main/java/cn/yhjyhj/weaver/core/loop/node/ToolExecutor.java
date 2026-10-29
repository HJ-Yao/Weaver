package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 执行已批准的调用，并返回与 calls 等长的结构化结果。 */
final class ToolExecutor {

    private final ToolRegistry registry; // 按模型工具名解析执行实例

    /**
     * 创建工具执行器。
     *
     * @param registry 当前运行使用的工具注册表
     */
    ToolExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 按调用顺序解析工具；未知工具在结果列表中保留 null。
     *
     * @param calls 待解析工具调用
     * @return 与 calls 等长且同序的工具列表
     */
    List<Tool> resolve(List<StreamChunk.ToolCall> calls) {
        return calls.stream()
                .map(call -> registry.find(call.toolName()).orElse(null))
                .toList();
    }

    /**
     * 执行已批准的调用，并返回与 calls 等长的结构化结果。
     *
     * <p>被拒绝和未知工具直接形成错误结果；其余调用仅在全部可并行且数量大于一时
     * 使用虚拟线程并发执行。单个工具返回 null 或抛出运行时异常时收敛为错误结果，
     * 无论实际完成顺序如何，返回值始终与 calls 同序。
     *
     * @param calls 原始工具调用
     * @param tools 与 calls 下标对应的已解析工具，未知工具为 null
     * @param outcomes 与 calls 下标对应的审批结论
     * @return 与 calls 等长且同序的执行结果
     */
    List<ToolResult> execute(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<ApprovalOutcome> outcomes) {
        requireMatchingSizes(calls, tools, outcomes);
        List<ToolResult> results = new ArrayList<>(calls.size());
        List<Integer> executable = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            results.add(null);
            prepareResult(calls, tools, outcomes, index, results, executable);
        }

        if (shouldExecuteInParallel(calls, tools, executable)) {
            executeInParallel(calls, tools, executable, results);
        } else {
            executeSerially(calls, tools, executable, results);
        }
        return List.copyOf(results);
    }

    private static void prepareResult(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<ApprovalOutcome> outcomes,
            int index,
            List<ToolResult> results,
            List<Integer> executable) {
        ApprovalOutcome outcome = outcomes.get(index);
        if (!outcome.approved()) {
            results.set(
                    index,
                    new ToolResult("denied by " + outcome.reason(), true, 0L));
        } else if (tools.get(index) == null) {
            results.set(index, unknownTool(calls.get(index)));
        } else {
            executable.add(index);
        }
    }

    private static void requireMatchingSizes(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<ApprovalOutcome> outcomes) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(outcomes, "outcomes");
        if (calls.size() != tools.size() || calls.size() != outcomes.size()) {
            throw new IllegalArgumentException(
                    "calls, tools and outcomes must have equal size");
        }
    }

    private static boolean shouldExecuteInParallel(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable) {
        return executable.size() > 1
                && executable.stream().allMatch(index -> tools.get(index)
                        .parallelizable(calls.get(index).argumentsJson()));
    }

    private void executeSerially(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable,
            List<ToolResult> results) {
        for (int index : executable) {
            results.set(index, executeOne(calls.get(index), tools.get(index)));
        }
    }

    private void executeInParallel(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable,
            List<ToolResult> results) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(executable.size());
            for (int index : executable) {
                futures.add(executor.submit(
                        () -> executeOne(calls.get(index), tools.get(index))));
            }

            for (int offset = 0; offset < executable.size(); offset++) {
                results.set(executable.get(offset), await(futures.get(offset)));
            }
        }
    }

    private static ToolResult executeOne(StreamChunk.ToolCall call, Tool tool) {
        long startedAt = System.nanoTime();
        try {
            ToolResult result = tool.execute(call.argumentsJson());
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (result == null) {
                return new ToolResult(
                        "tool returned null: " + call.toolName(), true, durationMs);
            }
            return new ToolResult(result.content(), result.isError(), durationMs);
        } catch (RuntimeException exception) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new ToolResult(
                    "tool execution failed: " + exception.getMessage(), true, durationMs);
        }
    }

    private static ToolResult unknownTool(StreamChunk.ToolCall call) {
        return new ToolResult("unknown tool: " + call.toolName(), true, 0L);
    }

    private static ToolResult await(Future<ToolResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ToolResult("tool execution interrupted", true, 0L);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return new ToolResult(
                    "tool execution failed: " + cause.getMessage(), true, 0L);
        }
    }
}
