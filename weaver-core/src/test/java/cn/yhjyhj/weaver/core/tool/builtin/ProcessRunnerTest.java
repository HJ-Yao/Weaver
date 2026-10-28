package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.tool.WorkspaceTempDirFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {

    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024; // 测试用合并输出上限

    @TempDir(factory = WorkspaceTempDirFactory.class)
    Path workspace; // 每个测试独立的命令工作目录

    @Test
    void removesCredentialVariablesButKeepsGitAuthorIdentity() {
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENAI_API_KEY", "secret");
        environment.put("GITHUB_TOKEN", "secret");
        environment.put("DB_PASSWORD", "secret");
        environment.put("GIT_AUTHOR_NAME", "henry");
        environment.put("GIT_AUTHOR_EMAIL", "henry@example.com");
        environment.put("PATH", "/usr/bin");

        ProcessRunner.removeSensitiveEnvironment(environment);

        assertFalse(environment.containsKey("OPENAI_API_KEY"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("DB_PASSWORD"));
        assertEquals("henry", environment.get("GIT_AUTHOR_NAME"));
        assertEquals("henry@example.com", environment.get("GIT_AUTHOR_EMAIL"));
        assertEquals("/usr/bin", environment.get("PATH"));
    }

    @Test
    void timeoutKeepsCapturedOutputAndSeparatesTimeoutState() throws Exception {
        ProcessRunner runner =
                new ProcessRunner(Duration.ofMillis(300L), OUTPUT_LIMIT_BYTES);

        ProcessRunner.Result result = runner.run(workspace, slowCommand());

        assertEquals(-1, result.exitCode());
        assertTrue(result.timedOut());
        assertFalse(result.readError());
        assertTrue(result.content().contains("started"));
        assertTrue(result.content().contains("[timeout after 300ms]"));
    }

    @Test
    void readFailureUsesReadErrorWithoutReportingTimeout() throws Exception {
        ProcessRunner runner = new ProcessRunner(
                Duration.ofSeconds(5L),
                OUTPUT_LIMIT_BYTES,
                input -> {
                    throw new IOException("simulated read failure");
                });

        ProcessRunner.Result result = runner.run(workspace, echoCommand());

        assertEquals(-1, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.readError());
        assertTrue(result.content().contains("output read failed: simulated read failure"));
    }

    @Test
    void successfulProcessReportsRealExitCodeAndNoErrorFlags() throws Exception {
        ProcessRunner runner = new ProcessRunner(
                Duration.ofSeconds(5L), OUTPUT_LIMIT_BYTES);

        ProcessRunner.Result result = runner.run(workspace, echoCommand());

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertFalse(result.readError());
        assertEquals("hello", result.content().trim());
    }

    @Test
    void truncatedOutputKeepsACompleteUtf8Prefix() throws Exception {
        byte[] output = "a\u4e2d".getBytes(StandardCharsets.UTF_8);
        int maxOutputBytes = 18;
        ProcessRunner runner = new ProcessRunner(
                Duration.ofSeconds(5L),
                maxOutputBytes,
                input -> new ProcessRunner.CapturedOutput(output, true, false));

        ProcessRunner.Result result = runner.run(workspace, echoCommand());

        assertEquals("a\n...[truncated]", result.content());
        assertFalse(result.readError());
    }

    @Test
    void timeoutWithoutCapturedOutputStillReportsTheConfiguredLimit() throws Exception {
        ProcessRunner runner = new ProcessRunner(
                Duration.ofMillis(300L),
                OUTPUT_LIMIT_BYTES,
                input -> new ProcessRunner.CapturedOutput(new byte[0], false, false));

        ProcessRunner.Result result = runner.run(workspace, slowCommand());

        assertEquals("[timeout after 300ms]", result.content());
        assertTrue(result.timedOut());
    }

    @Test
    void rejectsNonPositiveTimeoutAndOutputLimitTooSmallForMarker() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessRunner(Duration.ZERO, OUTPUT_LIMIT_BYTES));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessRunner(Duration.ofSeconds(1L), 15));
    }

    private static List<String> slowCommand() {
        return isWindows()
                ? List.of("cmd.exe", "/c", "echo started & ping -n 6 127.0.0.1 >nul")
                : List.of("sh", "-c", "echo started; sleep 5");
    }

    private static List<String> echoCommand() {
        return isWindows()
                ? List.of("cmd.exe", "/c", "echo hello")
                : List.of("sh", "-c", "echo hello");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
