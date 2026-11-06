package cn.yhjyhj.weaver.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.mcp.McpException;
import cn.yhjyhj.weaver.mcp.McpClient;
import cn.yhjyhj.weaver.mcp.fake.FakeMcpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class StdioTransportTest {

    private final Path cwd = Path.of("target", "stdio-test-work").toAbsolutePath(); // 受沙箱允许的测试工作目录

    @Test
    void closeTerminatesServerAndSpawnedChild() {
        StdioTransport transport = transport("spawn-child");
        try (McpClient client = new McpClient(transport, List.of(), Duration.ofSeconds(2))) {
            client.connect(Duration.ofSeconds(10));
            long childPid = awaitChildPid(transport);
            long serverPid = diagnosticPid(transport, "server-pid=");
            long start = System.nanoTime();
            client.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((isAlive(serverPid) || isAlive(childPid)) && System.nanoTime() < deadline) Thread.onSpinWait();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            System.out.println("process-tree-close-ms=" + elapsed.toMillis());
            assertFalse(isAlive(serverPid));
            assertFalse(isAlive(childPid));
            assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0);
        }
    }

    @Test
    void closeBeforeStartAndRepeatedCloseAreSafe() {
        StdioTransport transport = transport("normal");
        transport.close();
        transport.close();
        assertThrows(McpException.class, () -> transport.send("{}"));
        assertThrows(McpException.class, () -> transport.start(ignored -> { }, ignored -> { }));
    }

    @Test
    void nonZeroExitReportsDiagnosticsAndBoundsStderrTail() throws Exception {
        StdioTransport crash = transport("crash-immediately");
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> cause = new AtomicReference<>();
        crash.start(ignored -> { }, error -> { cause.set(error); closed.countDown(); });
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertTrue(cause.get().getMessage().contains("code 23"));
        assertTrue(crash.diagnostics().contains("startup failed"));

        StdioTransport noisy = transport("stderr-tail");
        try (McpClient client = new McpClient(noisy, List.of(), Duration.ofSeconds(2))) {
            client.connect(Duration.ofSeconds(2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (noisy.diagnostics().length() < 8 * 1024 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(8 * 1024, noisy.diagnostics().length());
        }
    }

    @Test
    void rejectsUtf8LineLargerThanSixteenMegabytes() throws Exception {
        StdioTransport transport = transport("oversized-line");
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> cause = new AtomicReference<>();
        transport.start(ignored -> { }, error -> { cause.set(error); closed.countDown(); });
        assertTrue(closed.await(15, TimeUnit.SECONDS));
        assertTrue(cause.get().getMessage().contains("exceeds 16 MB"));
        transport.close();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsCmdArgumentsWithMetaCharacters() throws Exception {
        Files.createDirectories(cwd);
        Path script = Files.createFile(cwd.resolve("server-" + System.nanoTime() + ".cmd"));
        for (String argument : List.of("a&b", "a|b", "a<b", "a>b", "a^b", "a%b", "a\"b")) {
            assertThrows(McpException.class, () -> StdioTransport.resolveCommand(script.toString(), List.of(argument),
                    "Windows 11", Map.of(), cwd));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvesCmdWithoutExtensionFromEffectivePath() throws Exception {
        Files.createDirectories(cwd);
        String commandName = "fake-server-" + System.nanoTime();
        Files.createFile(cwd.resolve(commandName + ".cmd"));
        List<String> invocation = StdioTransport.resolveCommand(commandName, List.of("safe"), "Windows 11",
                Map.of("PATH", cwd.toString(), "PATHEXT", ".CMD"), cwd);
        assertTrue(invocation.get(0).equalsIgnoreCase("cmd.exe"));
        assertTrue(invocation.get(3).toLowerCase(java.util.Locale.ROOT).endsWith((commandName + ".cmd").toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void nonWindowsCommandIsPassedDirectly() {
        assertTrue(StdioTransport.resolveCommand("server", List.of("one"), "Linux", Map.of(), cwd)
                .equals(List.of("server", "one")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvesExecutableAndRejectsMissingCommand() throws Exception {
        Files.createDirectories(cwd);
        Path executable = Files.createFile(cwd.resolve("fake-" + System.nanoTime() + ".exe"));
        assertEquals(executable.toAbsolutePath().toString(), StdioTransport.resolveCommand(executable.toString(),
                List.of(), "Windows", Map.of(), cwd).get(0));
        assertThrows(McpException.class, () -> StdioTransport.resolveCommand("missing-command", List.of(),
                "Windows", Map.of("PATH", cwd.toString(), "PATHEXT", ".EXE"), cwd));
    }

    @Test
    void commandRecordValidatesAndDefaultsCollections() {
        assertThrows(IllegalArgumentException.class, () -> new StdioServerCommand(" ", List.of(), Map.of(), cwd));
        StdioServerCommand command = new StdioServerCommand("java", null, null, cwd);
        assertTrue(command.args().isEmpty());
        assertTrue(command.env().isEmpty());
    }

    private StdioTransport transport(String mode) {
        try {
            Files.createDirectories(cwd);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classPath = System.getProperty("java.class.path");
        return new StdioTransport(new StdioServerCommand(java,
                List.of("-cp", classPath, FakeMcpServer.class.getName(), mode), Map.of(), cwd));
    }

    private long awaitChildPid(StdioTransport transport) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!transport.diagnostics().contains("sleeper-pid=") && System.nanoTime() < deadline) Thread.onSpinWait();
        return diagnosticPid(transport, "sleeper-pid=");
    }

    private long diagnosticPid(StdioTransport transport, String prefix) {
        String diagnostics = transport.diagnostics();
        int start = diagnostics.indexOf(prefix) + prefix.length();
        int end = diagnostics.indexOf('\n', start);
        return Long.parseLong(diagnostics.substring(start, end < 0 ? diagnostics.length() : end).trim());
    }

    private boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
