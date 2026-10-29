package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.events.SessionEvents;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.model.EchoModelClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunServiceTest {

    @Test
    void runsLoopOnVirtualThreadAndCompletesSseEmitter() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Echo");
        RecordingEmitter emitter = new RecordingEmitter();
        EchoModelClient echo = new EchoModelClient();
        AtomicBoolean executedOnVirtualThread = new AtomicBoolean();
        ModelClient virtualThreadProbe = request -> {
            executedOnVirtualThread.set(Thread.currentThread().isVirtual());
            return echo.stream(request);
        };
        RunService service = new RunService(
                sessions,
                store,
                virtualThreadProbe,
                ToolRegistry.empty(),
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none(),
                new WeaverProperties(Path.of("."), 10),
                () -> emitter);

        SseEmitter returned = service.run(summary.id(), "hello");

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        assertEquals(emitter, returned);
        assertNull(emitter.failure);
        assertTrue(executedOnVirtualThread.get());
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertEquals(
                List.of(
                        new SessionSnapshot.Message(SessionEvent.Role.USER, "hello"),
                        new SessionSnapshot.Message(
                                SessionEvent.Role.ASSISTANT, "echo: hello")),
                snapshot.messages());
        assertTrue(store.from(summary.id(), 0L).stream()
                .map(StoredEvent::event)
                .anyMatch(SessionEvent.RunFinished.class::isInstance));
    }

    @Test
    void rejectsUnknownSessionBeforeCreatingEmitter() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        RunService service = new RunService(
                sessions,
                store,
                new EchoModelClient(),
                ToolRegistry.empty(),
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none(),
                new WeaverProperties(Path.of("."), 10));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.run("missing", "hello"));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final CountDownLatch completion = new CountDownLatch(1); // 异步完成信号
        private volatile Throwable failure; // 异步失败原因

        private RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            // 事件内容由 SseStreamSinkTest 验证，这里只需观察异步生命周期。
        }

        @Override
        public void complete() {
            completion.countDown();
        }

        @Override
        public void completeWithError(Throwable failure) {
            this.failure = failure;
            completion.countDown();
        }

        private boolean awaitCompletion(Duration timeout) throws InterruptedException {
            return completion.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
