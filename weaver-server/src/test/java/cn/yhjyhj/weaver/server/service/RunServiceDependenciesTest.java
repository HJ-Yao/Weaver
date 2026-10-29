package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunServiceDependenciesTest {

    @Test
    void rejectsNonPositiveMaxSteps() {
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(eventStore);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> dependencies(sessions, eventStore, 0, Duration.ZERO)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> dependencies(sessions, eventStore, -1, Duration.ZERO)));
    }

    @Test
    void rejectsNegativeApprovalTimeout() {
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(eventStore);

        assertThrows(
                IllegalArgumentException.class,
                () -> dependencies(sessions, eventStore, 10, Duration.ofMillis(-1)));
    }

    @Test
    void rejectsNullCollaborators() {
        InMemorySessionEventStore eventStore = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(eventStore);
        ModelClient model = request -> {
            throw new UnsupportedOperationException();
        };
        ToolRegistry tools = ToolRegistry.empty();
        ApprovalPolicy approvalPolicy = RuleBasedApprovalPolicy.defaults();
        ApprovalGrantStore approvalGrants = ApprovalGrantStore.none();
        Supplier<SseEmitter> emitterFactory =
                RunServiceDependencies.defaultEmitterFactory();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                null,
                                eventStore,
                                model,
                                tools,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                null,
                                model,
                                tools,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                null,
                                tools,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                model,
                                null,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                model,
                                tools,
                                null,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                model,
                                tools,
                                approvalPolicy,
                                null,
                                10,
                                Duration.ofSeconds(1),
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                model,
                                tools,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                null,
                                emitterFactory)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunServiceDependencies(
                                sessions,
                                eventStore,
                                model,
                                tools,
                                approvalPolicy,
                                approvalGrants,
                                10,
                                Duration.ofSeconds(1),
                                null)));
    }

    @Test
    void defaultEmitterFactoryCreatesEmitter() {
        assertNotNull(RunServiceDependencies.defaultEmitterFactory().get());
    }

    private static RunServiceDependencies dependencies(
            SessionService sessions,
            SessionEventStore eventStore,
            int maxSteps,
            Duration approvalTimeout) {
        return new RunServiceDependencies(
                sessions,
                eventStore,
                request -> {
                    throw new UnsupportedOperationException();
                },
                ToolRegistry.empty(),
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGrantStore.none(),
                maxSteps,
                approvalTimeout,
                RunServiceDependencies.defaultEmitterFactory());
    }
}
