package cn.yhjyhj.weaver.server.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JDK HttpClient 的 SSE 传输层。
 *
 * <p>每次 {@link #post(URI, Map, String)} 都在虚拟线程中执行，使用
 * {@link SubmissionPublisher} 保留背压。传输层只向下游暴露 {@code data:} 行的负载，
 * 事件名、注释和空行由这里过滤。
 */
public final class HttpSseTransport implements AutoCloseable {

    private final HttpClient httpClient; // 复用连接池的 JDK HTTP 客户端
    private final Duration timeout; // 建连与请求等待响应的统一超时
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet(); // 可被关闭打断的读取线程
    private final AtomicBoolean closed = new AtomicBoolean(); // 防止关闭后继续创建请求

    /**
     * 创建 SSE 传输层。
     *
     * @param timeout 单次请求超时；必须为正时长
     */
    public HttpSseTransport(Duration timeout) {
        this.timeout = requirePositive(timeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /**
     * 发送 JSON POST，并返回逐行 SSE data 负载的 Publisher。
     *
     * <p>非 2xx 响应会以 {@link IllegalStateException} 终止流，错误信息包含最多
     * 4KB 响应体。订阅取消或传输层关闭时会中断对应的虚拟读取线程。
     *
     * @param uri 完整请求地址
     * @param headers 附加请求头
     * @param jsonBody JSON 请求体
     * @return 按背压消费的 SSE data 行流
     */
    public Flow.Publisher<String> post(
            URI uri,
            Map<String, String> headers,
            String jsonBody) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(jsonBody, "jsonBody");
        if (closed.get()) {
            return failedPublisher(new IllegalStateException("SSE transport is closed"));
        }

        return subscriber -> new SseExchange(
                subscriber, uri, headers, jsonBody, timeout, httpClient, activeThreads).start();
    }

    /**
     * 中断所有仍在读取响应的虚拟线程。
     */
    @Override
    public void close() {
        closed.set(true);
        activeThreads.forEach(Thread::interrupt);
    }

    private static Flow.Publisher<String> failedPublisher(Throwable error) {
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

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }
}
