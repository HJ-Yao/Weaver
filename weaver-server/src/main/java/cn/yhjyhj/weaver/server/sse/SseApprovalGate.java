package cn.yhjyhj.weaver.server.sse;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 通过当前 SSE 连接请求用户审批，并阻塞调用线程直到收到决策、超时或连接取消。
 *
 * <p>每个工具调用使用独立 future，因此同一会话中的多个并行调用不会互相覆盖决策。
 */
@Slf4j
public final class SseApprovalGate implements ApprovalGate {

    private final SseSender sender; // 与实时模型输出共享的同一条 SSE 传输
    private final Duration timeout; // 单次审批等待上限
    private final Map<String, CompletableFuture<Boolean>> pending = // callId 到待决 future
            new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(); // SSE 断开后阻止新请求排队

    /**
     * 创建绑定到单次 SSE 响应的审批闸门。
     *
     * @param sender 当前响应的 SSE 发送器
     * @param timeout 单次审批等待上限
     */
    public SseApprovalGate(SseSender sender, Duration timeout) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInteractive() {
        return true;
    }

    /**
     * 推送审批事件并等待用户决策；超时、中断或发送失败都按拒绝处理。
     *
     * @param call 待批准的工具调用
     * @param reversibility 工具声明的回滚性
     * @return 用户是否批准
     */
    @Override
    public boolean requestApproval(
            StreamChunk.ToolCall call, Reversibility reversibility) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(reversibility, "reversibility");

        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        pending.put(call.callId(), decision);
        try {
            if (cancelled.get()) {
                return false;
            }
            if (!sendApproval(call, reversibility)) {
                return false;
            }
            return awaitDecision(decision);
        } finally {
            // 只移除本次 future，避免 callId 重用后误删更新的审批项。
            pending.remove(call.callId(), decision);
        }
    }

    /**
     * 提交一次用户决策。
     *
     * @param callId 工具调用标识
     * @param approved 用户是否批准
     * @return 找到并接受该决策时返回 true；审批项已完成或不存在时返回 false
     */
    public boolean resolve(String callId, boolean approved) {
        CompletableFuture<Boolean> decision = pending.remove(callId);
        if (decision == null) {
            return false;
        }
        decision.complete(approved);
        return true;
    }

    /** 将全部待审批项拒绝，并阻止该 SSE 连接继续发起新审批。 */
    public void cancelAll() {
        cancelled.set(true);
        pending.forEach((callId, decision) -> {
            if (pending.remove(callId, decision)) {
                decision.complete(false);
            }
        });
    }

    private boolean sendApproval(
            StreamChunk.ToolCall call, Reversibility reversibility) {
        ApprovalEvent payload = new ApprovalEvent(
                call.callId(),
                call.toolName(),
                call.argumentsJson(),
                reversibility.name(),
                timeout.toSeconds());
        try {
            // 与模型流共用同一发送锁，避免两个虚拟线程并发写同一个 SSE 响应。
            synchronized (sender) {
                sender.send("approval", payload);
            }
            return true;
        } catch (IOException exception) {
            log.debug("failed to send approval request: callId={}", call.callId(), exception);
            return false;
        }
    }

    private boolean awaitDecision(CompletableFuture<Boolean> decision) {
        try {
            return decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException exception) {
            log.debug("approval future failed", exception);
            return false;
        }
    }

    private record ApprovalEvent(
            String callId,
            String toolName,
            String argumentsJson,
            String reversibility,
            long timeoutSeconds) {}
}
