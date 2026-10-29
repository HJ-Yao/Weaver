package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 Anthropic Messages 协议访问官方或兼容模型端点。
 *
 * <p>客户端负责配置、endpoint 与流生命周期装配；请求映射和 SSE 解析分别由
 * {@link AnthropicRequestMapper} 与 {@link AnthropicStreamSubscriber} 承担。
 */
public final class AnthropicMessagesModelClient implements ModelClient {

    private static final AtomicLong STREAM_IDS = new AtomicLong(); // 生成可辨认的虚拟线程名

    private final ModelProperties properties; // 模型端点、密钥与生成参数
    private final HttpSseTransport transport; // 可注入的 SSE 传输边界
    private final URI endpoint; // 已追加 v1/messages 的完整请求地址
    private final AnthropicRequestMapper requestMapper; // 内部请求到协议 JSON 的映射器

    /**
     * 使用应用配置创建客户端，并建立带配置超时的默认 HTTP SSE 传输层。
     *
     * @param properties 模型配置
     */
    public AnthropicMessagesModelClient(ModelProperties properties) {
        this(properties, new HttpSseTransport(
                Duration.ofSeconds(properties.timeoutSeconds())));
    }

    /**
     * 使用指定 SSE 传输层创建客户端，供测试或自定义连接策略使用。
     *
     * @param properties 模型配置
     * @param transport SSE 传输层
     */
    public AnthropicMessagesModelClient(
            ModelProperties properties,
            HttpSseTransport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = messagesEndpoint(properties.baseUrl());
        this.requestMapper = new AnthropicRequestMapper(properties);
    }

    /**
     * 发起 Anthropic Messages 流式请求。
     *
     * <p>请求体在订阅前完成构造；API key 为空时通过 Publisher 报告配置错误。
     * 正常结束先输出已完成工具调用，再输出 {@link StreamChunk.Done}。
     *
     * @param request 本轮模型输入、历史与工具清单
     * @return 带背压的模型响应分片流
     */
    @Override
    public Flow.Publisher<StreamChunk> stream(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (isBlank(properties.apiKey())) {
            return failedPublisher(new IllegalStateException(
                    "model api key is not configured"));
        }
        String jsonBody = requestMapper.requestBody(request);
        return subscriber -> openStream(subscriber, jsonBody);
    }

    private void openStream(Flow.Subscriber<? super StreamChunk> subscriber, String jsonBody) {
        Objects.requireNonNull(subscriber, "subscriber");
        SubmissionPublisher<StreamChunk> output = new SubmissionPublisher<>();
        AtomicReference<Flow.Subscription> transportSubscription = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        Thread readingThread = Thread.ofVirtual()
                .name("weaver-anthropic-" + STREAM_IDS.incrementAndGet())
                .unstarted(() -> readModelStream(
                        jsonBody,
                        output,
                        transportSubscription,
                        cancelled,
                        terminal));
        worker.set(readingThread);

        new ModelOutputBridge<StreamChunk>(subscriber, cancelled, () -> {
            Flow.Subscription upstream = transportSubscription.get();
            if (upstream != null) {
                upstream.cancel();
            }
            worker.get().interrupt();
        }).subscribeTo(output);

        if (!cancelled.get()) {
            readingThread.start();
        }
    }

    private void readModelStream(
            String jsonBody,
            SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean cancelled,
            AtomicBoolean terminal) {
        AnthropicStreamSubscriber bridge = new AnthropicStreamSubscriber(
                output, transportSubscription, cancelled, terminal);
        try {
            transport.post(endpoint, requestHeaders(), jsonBody).subscribe(bridge);
        } catch (RuntimeException exception) {
            fail(output, transportSubscription, terminal, exception);
        }
    }

    private Map<String, String> requestHeaders() {
        return Map.of(
                "x-api-key", properties.apiKey(),
                "anthropic-version", "2023-06-01",
                "content-type", "application/json",
                "accept", "text/event-stream");
    }

    private static URI messagesEndpoint(URI baseUrl) {
        String prefix = baseUrl.toString();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return URI.create(prefix + "/v1/messages");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void fail(
            SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean terminal,
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

    private static Flow.Publisher<StreamChunk> failedPublisher(Throwable error) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
            subscriber.onError(error);
        };
    }
}
