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
 * 通过 OpenAI Chat Completions 协议访问兼容模型端点。
 *
 * <p>客户端负责配置、endpoint 与流生命周期装配；请求映射和 SSE 解析分别由
 * {@link OpenAiRequestMapper} 与 {@link OpenAiStreamSubscriber} 承担。
 */
public final class OpenAiChatCompletionsModelClient implements ModelClient {

    private static final AtomicLong STREAM_IDS = new AtomicLong(); // 生成可辨认的虚拟线程名

    private final ModelProperties properties; // 模型端点、密钥与生成参数
    private final HttpSseTransport transport; // 可注入的 SSE 传输边界
    private final URI endpoint; // 已追加 chat/completions 的完整请求地址
    private final OpenAiRequestMapper requestMapper; // 内部请求到协议 JSON 的映射器

    /**
     * 使用应用配置创建客户端，并建立默认 HTTP SSE 传输层。
     *
     * @param properties 模型配置
     */
    public OpenAiChatCompletionsModelClient(ModelProperties properties) {
        this(properties, new HttpSseTransport(
                Duration.ofSeconds(properties.timeoutSeconds())));
    }

    /**
     * 使用指定 SSE 传输层创建客户端，供测试或自定义连接策略使用。
     *
     * @param properties 模型配置
     * @param transport SSE 传输层
     */
    public OpenAiChatCompletionsModelClient(
            ModelProperties properties,
            HttpSseTransport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = chatCompletionsEndpoint(properties.baseUrl());
        this.requestMapper = new OpenAiRequestMapper(properties);
    }

    /**
     * 发起流式补全请求。
     *
     * <p>请求体在订阅前完成构造；API key 为空时通过 Publisher 报告配置错误。
     * 正常结束时先输出聚合后的工具调用，再输出 {@link StreamChunk.Done}。
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
                .name("weaver-model-" + STREAM_IDS.incrementAndGet())
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
        OpenAiStreamSubscriber bridge = new OpenAiStreamSubscriber(
                output, transportSubscription, cancelled, terminal);
        transport.post(endpoint, requestHeaders(), jsonBody).subscribe(bridge);
    }

    private Map<String, String> requestHeaders() {
        return Map.of(
                "Authorization", "Bearer " + properties.apiKey(),
                "Content-Type", "application/json",
                "Accept", "text/event-stream");
    }

    private static URI chatCompletionsEndpoint(URI baseUrl) {
        String prefix = baseUrl.toString();
        if (prefix.endsWith("/")) {
            return URI.create(prefix + "chat/completions");
        }
        return URI.create(prefix + "/chat/completions");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
