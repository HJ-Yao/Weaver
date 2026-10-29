package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 消费 Anthropic Messages SSE 事件并聚合为 {@link StreamChunk}。
 *
 * <p>实例由模型客户端为单次请求创建，生命周期由模型客户端持有，并随上游
 * 传输流终止或下游取消结束。已完成的工具调用按内容块索引顺序下发。
 */
final class AnthropicStreamSubscriber implements Flow.Subscriber<String> {
    private static final ObjectMapper JSON = new ObjectMapper(); // SSE 事件 JSON 解析器
    private final SubmissionPublisher<StreamChunk> output; // 模型流内部发布者
    private final AtomicReference<Flow.Subscription> transportSubscription; // 上游传输订阅句柄
    private final AtomicBoolean cancelled; // 下游是否已取消
    private final AtomicBoolean terminal; // 是否已发出终止信号
    private final AnthropicStreamState state = new AnthropicStreamState(); // 当前响应聚合状态

    AnthropicStreamSubscriber(SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean cancelled, AtomicBoolean terminal) {
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
        if (cancelled.get() || terminal.get()) {
            return;
        }
        try {
            if (processPayload(payload, state, output)) {
                terminal.set(true);
                transportSubscription.get().cancel();
                output.close();
                return;
            }
            transportSubscription.get().request(1);
        } catch (RuntimeException exception) {
            fail(output, transportSubscription, terminal, exception);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        if (!cancelled.get()) {
            fail(output, transportSubscription, terminal, throwable);
        }
    }

    @Override
    public void onComplete() {
        if (cancelled.get() || !terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            emitCompletedToolCalls(state, output);
            output.submit(new StreamChunk.Done(state.stopReason()));
            output.close();
        } catch (RuntimeException exception) {
            output.closeExceptionally(exception);
        }
    }

    private static boolean processPayload(String payload, AnthropicStreamState state,
            SubmissionPublisher<StreamChunk> output) {
        JsonNode event = parseJson(payload, "invalid SSE JSON payload");
        JsonNode typeNode = event.path("type");
        if (!typeNode.isTextual()) {
            return false;
        }
        switch (typeNode.asText()) {
            case "message_start" -> {
                // 消息元数据不参与 Weaver 的流式输出契约。
            }
            case "content_block_start" -> startContentBlock(event, state);
            case "content_block_delta" -> processContentBlockDelta(event, state, output);
            case "content_block_stop" -> completeContentBlock(event, state);
            case "message_delta" -> state.stopReason(
                    mapStopReason(event.path("delta").path("stop_reason")));
            case "message_stop" -> {
                emitCompletedToolCalls(state, output);
                output.submit(new StreamChunk.Done(state.stopReason()));
                return true;
            }
            case "error" -> throw new IllegalStateException(
                    "model error: " + event.path("error").path("message").asText(""));
            default -> {
                // Anthropic 会扩展事件类型；未知事件不改变当前状态。
            }
        }
        return false;
    }

    private static void startContentBlock(JsonNode event, AnthropicStreamState state) {
        JsonNode block = event.path("content_block");
        if (!"tool_use".equals(block.path("type").asText())) {
            return;
        }
        state.toolCalls().put(event.path("index").asInt(), new AnthropicToolCallAccumulator(
                block.path("id").asText(), block.path("name").asText()));
    }

    private static void processContentBlockDelta(
            JsonNode event,
            AnthropicStreamState state,
            SubmissionPublisher<StreamChunk> output) {
        JsonNode delta = event.path("delta");
        switch (delta.path("type").asText()) {
            case "text_delta" -> {
                JsonNode text = delta.path("text");
                if (text.isTextual() && !text.asText().isEmpty()) {
                    output.submit(new StreamChunk.Text(text.asText()));
                }
            }
            case "input_json_delta" -> {
                int index = event.path("index").asInt();
                AnthropicToolCallAccumulator accumulator = state.toolCalls().computeIfAbsent(
                        index, ignored -> new AnthropicToolCallAccumulator("", ""));
                JsonNode partialJson = delta.path("partial_json");
                if (partialJson.isTextual()) {
                    accumulator.appendArguments(partialJson.asText());
                }
            }
            default -> {
                // 新类型增量不影响当前文本与工具参数状态。
            }
        }
    }

    private static void completeContentBlock(JsonNode event, AnthropicStreamState state) {
        AnthropicToolCallAccumulator accumulator =
                state.toolCalls().get(event.path("index").asInt());
        if (accumulator != null) {
            accumulator.complete();
        }
    }

    private static void emitCompletedToolCalls(
            AnthropicStreamState state,
            SubmissionPublisher<StreamChunk> output) {
        state.toolCalls().values().stream()
                .filter(AnthropicToolCallAccumulator::completed)
                .forEach(call -> output.submit(call.toToolCall()));
    }

    private static StreamChunk.StopReason mapStopReason(JsonNode stopReason) {
        if (!stopReason.isTextual()) {
            return StreamChunk.StopReason.STOP;
        }
        return switch (stopReason.asText()) {
            case "max_tokens" -> StreamChunk.StopReason.LENGTH;
            case "end_turn", "tool_use", "stop_sequence" -> StreamChunk.StopReason.STOP;
            default -> StreamChunk.StopReason.STOP;
        };
    }

    private static JsonNode parseJson(String source, String errorMessage) {
        try {
            JsonNode parsed = JSON.readTree(source);
            if (parsed == null) {
                throw new IllegalArgumentException(errorMessage);
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(errorMessage, exception);
        }
    }

    private static void fail(SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription, AtomicBoolean terminal,
            Throwable throwable) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        Flow.Subscription upstream = transportSubscription.get();
        if (upstream != null) {
            upstream.cancel();
        }
        output.closeExceptionally(throwable);
    }
}
