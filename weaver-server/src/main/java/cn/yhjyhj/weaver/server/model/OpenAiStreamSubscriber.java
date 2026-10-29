package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 消费 OpenAI SSE 负载并转换为 {@link StreamChunk}。
 *
 * <p>实例由模型客户端为单次请求创建，生命周期由模型客户端持有，并随上游
 * 传输流终止或下游取消结束。工具调用按 {@code index} 聚合后一次性下发。
 */
final class OpenAiStreamSubscriber implements Flow.Subscriber<String> {

    private static final ObjectMapper JSON = new ObjectMapper(); // SSE 负载 JSON 解析器

    private final SubmissionPublisher<StreamChunk> output; // 模型流内部发布者
    private final AtomicReference<Flow.Subscription> transportSubscription; // 上游传输订阅句柄
    private final AtomicBoolean cancelled; // 下游是否已取消
    private final AtomicBoolean terminal; // 是否已发出终止信号
    private final ParseState state = new ParseState(); // 当前响应聚合状态

    /**
     * 创建单次请求的 SSE 订阅器。
     *
     * @param output 模型流内部发布者
     * @param transportSubscription 上游传输订阅句柄
     * @param cancelled 下游取消标记
     * @param terminal 终止信号标记
     */
    OpenAiStreamSubscriber(
            SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean cancelled,
            AtomicBoolean terminal) {
        this.output = output;
        this.transportSubscription = transportSubscription;
        this.cancelled = cancelled;
        this.terminal = terminal;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        transportSubscription.set(subscription);
        if (cancelled.get()) {
            subscription.cancel();
            return;
        }
        subscription.request(1);
    }

    @Override
    public void onNext(String payload) {
        if (cancelled.get()) {
            return;
        }
        try {
            boolean done = processPayload(payload, state, output);
            if (done) {
                terminal.set(true);
                transportSubscription.get().cancel();
                output.close();
                return;
            }
            transportSubscription.get().request(1);
        } catch (RuntimeException exception) {
            fail(output, terminal, exception);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        fail(output, terminal, throwable);
    }

    @Override
    public void onComplete() {
        if (cancelled.get()) {
            return;
        }
        terminal.set(true);
        try {
            emitAggregatedToolCalls(state, output);
            output.submit(new StreamChunk.Done(state.stopReason));
            output.close();
        } catch (RuntimeException exception) {
            output.closeExceptionally(exception);
        }
    }

    private boolean processPayload(
            String payload,
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        if ("[DONE]".equals(payload)) {
            emitAggregatedToolCalls(state, output);
            output.submit(new StreamChunk.Done(state.stopReason));
            return true;
        }

        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid SSE JSON payload", exception);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return false;
        }

        JsonNode choice = choices.get(0);
        JsonNode content = choice.path("delta").path("content");
        if (content.isTextual()) {
            String text = content.asText();
            if (!text.isEmpty()) {
                output.submit(new StreamChunk.Text(text));
            }
        }
        JsonNode toolCalls = choice.path("delta").path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                appendToolCall(toolCall, state.toolCalls);
            }
        }

        JsonNode finishReason = choice.path("finish_reason");
        if (finishReason.isTextual() && !finishReason.asText().isBlank()) {
            state.stopReason = mapStopReason(finishReason.asText());
        }
        return false;
    }

    private static void appendToolCall(
            JsonNode toolCall,
            Map<Integer, OpenAiToolCallAccumulator> accumulated) {
        int index = toolCall.path("index").asInt(0);
        OpenAiToolCallAccumulator target = accumulated.computeIfAbsent(
                index, ignored -> new OpenAiToolCallAccumulator());
        appendText(toolCall.path("id"), target::appendId);
        JsonNode function = toolCall.path("function");
        appendText(function.path("name"), target::appendName);
        appendText(function.path("arguments"), target::appendArguments);
    }

    private static void appendText(JsonNode value, Consumer<String> target) {
        if (value.isTextual() && !value.asText().isEmpty()) {
            target.accept(value.asText());
        }
    }

    private static void emitAggregatedToolCalls(
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        state.toolCalls.values().forEach(call -> output.submit(call.toToolCall()));
    }

    private static StreamChunk.StopReason mapStopReason(String finishReason) {
        return switch (finishReason) {
            case "length" -> StreamChunk.StopReason.LENGTH;
            case "stop", "tool_calls" -> StreamChunk.StopReason.STOP;
            default -> StreamChunk.StopReason.STOP;
        };
    }

    private static void fail(
            SubmissionPublisher<StreamChunk> output,
            AtomicBoolean terminal,
            Throwable throwable) {
        terminal.set(true);
        output.closeExceptionally(throwable);
    }

    private static final class ParseState {

        private final Map<Integer, OpenAiToolCallAccumulator> toolCalls = new TreeMap<>(); // 按 index 聚合
        private StreamChunk.StopReason stopReason = StreamChunk.StopReason.STOP; // 默认自然结束
    }
}
