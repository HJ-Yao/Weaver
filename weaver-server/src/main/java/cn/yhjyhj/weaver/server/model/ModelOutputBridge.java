package cn.yhjyhj.weaver.server.model;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将模型客户端的内部 {@link SubmissionPublisher} 桥接给下游订阅者。
 *
 * <p>实例由单个模型流请求持有，生命周期与内部发布者一致。下游取消时，
 * 桥接器同时取消内部发布者并执行模型客户端提供的上游清理动作。
 */
final class ModelOutputBridge<T> implements Flow.Subscriber<T>, Flow.Subscription {

    private final Flow.Subscriber<? super T> downstream; // 接收模型响应分片的下游订阅者
    private final AtomicBoolean cancelled; // 所属模型流是否已取消
    private final Runnable cancelUpstream; // 取消上游传输并中断读取线程的清理动作
    private Flow.Subscription publisherSubscription; // 内部发布者的背压订阅句柄

    ModelOutputBridge(
            Flow.Subscriber<? super T> downstream,
            AtomicBoolean cancelled,
            Runnable cancelUpstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.cancelUpstream = Objects.requireNonNull(cancelUpstream, "cancelUpstream");
    }

    /**
     * 订阅内部发布者，并把自身作为下游的订阅句柄。
     *
     * @param publisher 当前模型流使用的内部发布者
     */
    void subscribeTo(SubmissionPublisher<T> publisher) {
        publisher.subscribe(this);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        publisherSubscription = subscription;
        downstream.onSubscribe(this);
    }

    @Override
    public void onNext(T item) {
        downstream.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        downstream.onError(throwable);
    }

    @Override
    public void onComplete() {
        downstream.onComplete();
    }

    @Override
    public void request(long count) {
        publisherSubscription.request(count);
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        publisherSubscription.cancel();
        cancelUpstream.run();
    }
}
