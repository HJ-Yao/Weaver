package cn.yhjyhj.weaver.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class EchoModelClientTest {

    @Test
    void emitsEchoTextThenStopAndCompletes() {
        EchoModelClient client = new EchoModelClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();

        client.stream(new ModelRequest("hello", List.of())).subscribe(subscriber);
        subscriber.subscription.request(1L);

        assertEquals(
                List.of(
                        new StreamChunk.Text("echo: hello"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                subscriber.chunks);
        assertTrue(subscriber.completed);
    }

    @Test
    void sendsResponseOnlyOnceWhenRequestedRepeatedly() {
        EchoModelClient client = new EchoModelClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();

        client.stream(new ModelRequest("hello", List.of())).subscribe(subscriber);
        subscriber.subscription.request(1L);
        subscriber.subscription.request(1L);
        subscriber.subscription.request(10L);

        assertEquals(2, subscriber.chunks.size());
        assertTrue(subscriber.completed);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<StreamChunk> {

        private final List<StreamChunk> chunks = new ArrayList<>(); // 收到的分片
        private Flow.Subscription subscription; // 可控的背压入口
        private boolean completed; // 是否收到正常结束

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(StreamChunk item) {
            chunks.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError("unexpected publisher error", throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
