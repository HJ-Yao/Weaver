package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** 不回显历史、不访问网络的占位模型客户端。 */
public final class EchoModelClient implements ModelClient {

    /** {@inheritDoc} */
    @Override
    public Flow.Publisher<StreamChunk> stream(ModelRequest request) {
        return subscriber -> subscriber.onSubscribe(new EchoSubscription(subscriber, request.input()));
    }

    private static final class EchoSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super StreamChunk> subscriber; // 一次性响应接收方
        private final String input; // 本次回显内容
        private final AtomicBoolean started = new AtomicBoolean(); // 防止重复 request 重放响应
        private final AtomicBoolean cancelled = new AtomicBoolean(); // 取消后不再产生回调

        private EchoSubscription(
                Flow.Subscriber<? super StreamChunk> subscriber,
                String input) {
            this.subscriber = subscriber;
            this.input = input;
        }

        @Override
        public void request(long count) {
            if (count <= 0L) {
                if (started.compareAndSet(false, true)) {
                    subscriber.onError(new IllegalArgumentException(
                            "request count must be positive"));
                }
                return;
            }
            if (cancelled.get() || !started.compareAndSet(false, true)) {
                return;
            }
            subscriber.onNext(new StreamChunk.Text("echo: " + input));
            if (cancelled.get()) {
                return;
            }
            subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
            if (!cancelled.get()) {
                subscriber.onComplete();
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
