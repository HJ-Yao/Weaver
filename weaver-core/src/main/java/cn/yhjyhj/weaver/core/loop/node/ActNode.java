package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 执行本步收集到的工具调用，并为每次调用记录开始与完成事实。 */
public final class ActNode extends AbstractRunNode {

    private final ToolRegistry registry; // 按模型工具名查找执行实例

    /**
     * 创建工具执行节点。
     *
     * @param registry 工具注册表
     * @param observeNode 工具执行完成后进入的结果收敛节点
     */
    public ActNode(
            ToolRegistry registry,
            StrategyHandler<RunRequest, RunContext, StepResult> observeNode) {
        super(observeNode);
        this.registry = registry;
    }

    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        List<StreamChunk.ToolCall> calls = List.copyOf(context.getPendingToolCalls());
        for (StreamChunk.ToolCall call : calls) {
            context.getSink().emit(new SessionEvent.ToolCallStarted(
                    call.callId(), call.toolName(), call.argumentsJson()));
        }

        List<Tool> tools = calls.stream()
                .map(call -> registry.find(call.toolName()).orElse(null))
                .toList();
        List<ToolResult> results = shouldExecuteInParallel(calls, tools)
                ? executeInParallel(calls, tools)
                : executeSerially(calls, tools);

        // 并行完成顺序不影响事实顺序；结果始终按模型给出的调用顺序落库。
        for (int index = 0; index < calls.size(); index++) {
            StreamChunk.ToolCall call = calls.get(index);
            context.getSink().emit(new SessionEvent.ToolCallCompleted(
                    call.callId(), results.get(index)));
        }
        context.getPendingToolCalls().clear();
        context.setToolExecuted(true);
        return null;
    }

    private boolean shouldExecuteInParallel(List<StreamChunk.ToolCall> calls, List<Tool> tools) {
        if (calls.size() <= 1) {
            return false;
        }
        // 未知工具没有并行能力声明，保守退化为串行；任一已知工具拒绝并行也会退化。
        return tools.stream().allMatch(tool -> tool != null && tool.parallelizable());
    }

    private List<ToolResult> executeSerially(
            List<StreamChunk.ToolCall> calls, List<Tool> tools) {
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            results.add(executeOne(calls.get(index), tools.get(index)));
        }
        return results;
    }

    private List<ToolResult> executeInParallel(
            List<StreamChunk.ToolCall> calls, List<Tool> tools) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(calls.size());
            for (int index = 0; index < calls.size(); index++) {
                int taskIndex = index;
                futures.add(executor.submit(
                        () -> executeOne(calls.get(taskIndex), tools.get(taskIndex))));
            }

            List<ToolResult> results = new ArrayList<>(calls.size());
            for (Future<ToolResult> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private ToolResult executeOne(StreamChunk.ToolCall call, Tool tool) {
        if (tool == null) {
            return new ToolResult("unknown tool: " + call.toolName(), true, 0L);
        }

        long startedAt = System.nanoTime();
        try {
            ToolResult result = tool.execute(call.argumentsJson());
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (result == null) {
                return new ToolResult("tool returned null: " + call.toolName(), true, durationMs);
            }
            return new ToolResult(result.content(), result.isError(), durationMs);
        } catch (RuntimeException exception) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new ToolResult(
                    "tool execution failed: " + exception.getMessage(), true, durationMs);
        }
    }

    private ToolResult await(Future<ToolResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ToolResult("tool execution interrupted", true, 0L);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return new ToolResult("tool execution failed: " + cause.getMessage(), true, 0L);
        }
    }
}
