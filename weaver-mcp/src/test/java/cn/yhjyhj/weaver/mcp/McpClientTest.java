package cn.yhjyhj.weaver.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.mcp.fake.FakeMcpServer;
import cn.yhjyhj.weaver.mcp.transport.StdioServerCommand;
import cn.yhjyhj.weaver.mcp.transport.StdioTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path cwd = Path.of("target", "mcp-client-test-work").toAbsolutePath(); // 受沙箱允许的测试工作目录

    @Test
    void connectsAndSendsInitializedNotification() {
        Fixture fixture = fixture("normal", Duration.ofSeconds(2), List.of());
        try (McpClient client = fixture.client()) {
            McpServerInfo info = client.connect(Duration.ofSeconds(2));
            assertEquals("fake", info.name());
            assertEquals("1.2.3", info.version());
            assertEquals(McpClient.PROTOCOL_VERSION, info.protocolVersion());
            awaitDiagnostic(fixture.transport(), "initialized");
            assertTrue(client.isConnected());
        }
    }

    @Test
    void startupTimeoutClosesTransport() {
        Fixture fixture = fixture("slow-init", Duration.ofMillis(100), List.of());
        assertThrows(McpTimeoutException.class, () -> fixture.client().connect(Duration.ofMillis(100)));
        assertFalse(fixture.client().isConnected());
        assertThrows(McpException.class, () -> fixture.transport().send("{}"));
    }

    @Test
    void rejectsUnsupportedVersionWithVersionInMessage() {
        Fixture fixture = fixture("unsupported-version", Duration.ofSeconds(2), List.of());
        McpException error = assertThrows(McpException.class, () -> fixture.client().connect(Duration.ofSeconds(2)));
        assertTrue(error.getMessage().contains("2099-01-01"));
    }

    @Test
    void listsAllPagesAndStopsAtTwentyPages() {
        try (McpClient paged = connect("paged-tools", Duration.ofSeconds(2), List.of()).client()) {
            assertEquals(List.of("page-1", "page-2", "page-3"), paged.listTools().stream().map(McpToolDefinition::name).toList());
        }
        try (McpClient endless = connect("endless-pages", Duration.ofSeconds(2), List.of()).client()) {
            assertEquals(20, endless.listTools().size());
        }
    }

    @Test
    void callsToolAndPreservesToolError() {
        try (McpClient normal = connect("normal", Duration.ofSeconds(2), List.of()).client()) {
            McpToolResult result = normal.callTool("echo", MAPPER.createObjectNode().put("value", 7));
            assertFalse(result.isError());
            assertTrue(result.content().get(0).path("text").asText().contains("value"));
        }
        try (McpClient failing = connect("tool-error", Duration.ofSeconds(2), List.of()).client()) {
            assertTrue(failing.callTool("echo", MAPPER.createObjectNode()).isError());
        }
    }

    @Test
    void mapsToolMetadataAndDefaultsMissingSchema() {
        try (McpClient rich = connect("rich-tool", Duration.ofSeconds(2), List.of()).client()) {
            McpToolDefinition tool = rich.listTools().get(0);
            assertEquals("Echo title", tool.title());
            assertEquals("Echo description", tool.description());
            assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint());
            assertEquals(Boolean.FALSE, tool.annotations().destructiveHint());
        }
        try (McpClient missing = connect("missing-schema", Duration.ofSeconds(2), List.of()).client()) {
            assertEquals("object", missing.listTools().get(0).inputSchema().path("type").asText());
        }
    }

    @Test
    void pingAndDisconnectedOperationsHaveExplicitStateSemantics() {
        Fixture fixture = fixture("normal", Duration.ofSeconds(2), List.of());
        assertThrows(McpException.class, fixture.client()::ping);
        fixture.client().connect(null);
        fixture.client().ping();
        fixture.client().onToolsChanged(null);
        fixture.client().close();
        fixture.client().close();
        assertThrows(McpException.class, fixture.client()::listTools);
        assertThrows(McpException.class, () -> fixture.client().connect(Duration.ofSeconds(1)));
    }

    @Test
    void defaultsOptionalConstructorValuesAndClosesBeforeConnect() {
        Fixture fixture = fixture("normal", null, null);
        fixture.client().close();
        assertFalse(fixture.client().isConnected());
        assertThrows(McpException.class, fixture.client()::ping);
    }

    @Test
    void callToolMayOmitArguments() {
        try (McpClient client = connect("normal", Duration.ofSeconds(2), List.of()).client()) {
            McpToolResult result = client.callTool("echo", null);
            assertFalse(result.isError());
        }
    }

    @Test
    void rejectsMalformedInitializeAndToolListResults() {
        Fixture invalidInitialize = fixture("invalid-initialize", Duration.ofSeconds(2), List.of());
        assertThrows(McpException.class, () -> invalidInitialize.client().connect(Duration.ofSeconds(2)));
        try (McpClient invalidTools = connect("invalid-tools", Duration.ofSeconds(2), List.of()).client()) {
            assertThrows(McpException.class, invalidTools::listTools);
        }
    }

    @Test
    void timedOutToolCallNotifiesServerWithRequestId() {
        Fixture fixture = connect("hang-on-call", Duration.ofMillis(80), List.of());
        try (McpClient client = fixture.client()) {
            assertThrows(McpTimeoutException.class, () -> client.callTool("hang", MAPPER.createObjectNode()));
            awaitDiagnostic(fixture.transport(), "cancelled=2");
        }
    }

    @Test
    void processCrashFailsPendingCallQuickly() {
        Fixture fixture = connect("crash-after-init", Duration.ofSeconds(10), List.of());
        long start = System.nanoTime();
        McpException error = assertThrows(McpException.class, () -> fixture.client().callTool("crash", MAPPER.createObjectNode()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0);
        assertTrue(error.getMessage().contains("connection closed"));
        assertFalse(fixture.client().isConnected());
    }

    @Test
    void dispatchesToolsChangedNotification() throws Exception {
        Fixture fixture = fixture("list-changed", Duration.ofSeconds(2), List.of());
        CountDownLatch changed = new CountDownLatch(1);
        fixture.client().onToolsChanged(changed::countDown);
        try (McpClient client = fixture.client()) {
            client.connect(Duration.ofSeconds(2));
            assertTrue(changed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void repliesToRootsListWithConfiguredUri() throws Exception {
        Path root = cwd.resolve("workspace").toAbsolutePath();
        try (McpClient client = connect("ask-roots", Duration.ofSeconds(2), List.of(root)).client()) {
            McpToolResult result = client.callTool("roots", MAPPER.createObjectNode());
            JsonNode roots = MAPPER.readTree(result.content().get(0).path("text").asText());
            assertEquals(root.toUri().toString(), roots.get(0).path("uri").asText());
        }
    }

    @Test
    void correlatesOneHundredConcurrentToolCalls() throws Exception {
        try (McpClient client = connect("normal", Duration.ofSeconds(5), List.of()).client();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int expected = index;
                futures.add(executor.submit(() -> {
                    McpToolResult result = client.callTool("echo", MAPPER.createObjectNode().put("index", expected));
                    return MAPPER.readTree(result.content().get(0).path("text").asText()).path("index").asInt();
                }));
            }
            for (int index = 0; index < futures.size(); index++) assertEquals(index, futures.get(index).get());
        }
    }

    private Fixture connect(String mode, Duration requestTimeout, List<Path> roots) {
        Fixture fixture = fixture(mode, requestTimeout, roots);
        fixture.client().connect(Duration.ofSeconds(2));
        return fixture;
    }

    private Fixture fixture(String mode, Duration requestTimeout, List<Path> roots) {
        try {
            java.nio.file.Files.createDirectories(cwd);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classPath = System.getProperty("java.class.path");
        StdioTransport transport = new StdioTransport(new StdioServerCommand(java,
                List.of("-cp", classPath, FakeMcpServer.class.getName(), mode), Map.of(), cwd));
        return new Fixture(new McpClient(transport, roots, requestTimeout), transport);
    }

    private void awaitDiagnostic(StdioTransport transport, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!transport.diagnostics().contains(expected) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(transport.diagnostics().contains(expected), transport.diagnostics());
    }

    private record Fixture(McpClient client, StdioTransport transport) { }
}
