package cn.yhjyhj.weaver.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.RunLoop;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.api.SessionController;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.model.EchoModelClient;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.SessionService;
import cn.yhjyhj.weaver.server.store.SqliteEventStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WeaverApplicationTest {

    private static final Path workspace =
            Path.of("target", "weaver-test-workspace").toAbsolutePath(); // 隔离内置工具的工作区
    private static final Path eventStore =
            Path.of("target", "test-weaver.db").toAbsolutePath(); // 隔离事件持久化数据库

    @Autowired
    private ApplicationContext context; // 用于核对装配结果

    @BeforeAll
    static void createWorkspace() throws IOException {
        Files.createDirectories(workspace);
    }

    @DynamicPropertySource
    static void configureWorkspace(DynamicPropertyRegistry registry) {
        registry.add("weaver.workspace", () -> workspace.toString());
        registry.add("weaver.max-steps", () -> 25);
        registry.add("weaver.store-path", () -> eventStore.toString());
    }

    @Test
    void startsWithAllCoreCollaboratorsAndServerServices() throws IOException {
        WeaverProperties properties = context.getBean(WeaverProperties.class);
        assertEquals(workspace.toRealPath(), properties.workspace().toRealPath());
        assertEquals(25, properties.maxSteps());
        assertEquals(eventStore, properties.storePath());

        assertTrue(context.getBean(SessionEventStore.class) instanceof SqliteEventStore);
        ToolRegistry tools = context.getBean(ToolRegistry.class);
        assertEquals(
                java.util.List.of("file", "git", "shell"),
                tools.specs().stream().map(spec -> spec.name()).toList());
        assertTrue(context.getBean(ApprovalPolicy.class) != null);
        assertTrue(context.getBean(ApprovalGrantStore.class) != null);
        assertTrue(context.getBean(ApprovalGate.class) != null);
        assertTrue(context.getBean(ModelClient.class) instanceof EchoModelClient);
        assertTrue(context.getBean(SessionService.class) != null);
        assertTrue(context.getBean(RunService.class) != null);
        assertTrue(context.getBean(SessionController.class) != null);
        assertEquals(0, context.getBeanNamesForType(RunLoop.class).length);
    }
}
