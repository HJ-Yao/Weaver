package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.WorkspaceTempDirFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellToolTest {

    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024; // Shell 工具的输出上限
    private static final String TRUNCATION_MARKER = "\n...[truncated]"; // 截断提示

    @TempDir(factory = WorkspaceTempDirFactory.class)
    Path workspace; // 每个测试独立的临时工作区

    @Test
    void runsCommandInWorkspaceAndMergesOutput() {
        ShellTool tool = new ShellTool(workspace);

        ToolResult result = tool.execute(arguments("echo hello"));

        assertFalse(result.isError());
        assertEquals("hello", result.content().trim());
    }

    @Test
    void mergesStandardOutputAndStandardError() {
        ShellTool tool = new ShellTool(workspace);

        ToolResult result = tool.execute(arguments(mergedOutputCommand()));

        assertFalse(result.isError());
        assertTrue(result.content().contains("stdout"));
        assertTrue(result.content().contains("stderr"));
    }

    @Test
    void killsCommandAfterTimeout() {
        ShellTool tool = new ShellTool(workspace, Duration.ofMillis(300L), OUTPUT_LIMIT_BYTES);

        ToolResult result = tool.execute(arguments(slowCommand()));

        assertTrue(result.isError());
        assertTrue(result.content().contains("started"));
        assertTrue(result.content().contains("[timeout after 300ms]"));
    }

    @Test
    void removesSensitiveEnvironmentVariablesBeforeStartingCommand() {
        ShellTool tool = new ShellTool(
                workspace,
                Duration.ofSeconds(5L),
                OUTPUT_LIMIT_BYTES,
                Map.of("OPENAI_API_KEY", "must-not-leak"));

        ToolResult result = tool.execute(arguments(environmentEchoCommand()));

        assertFalse(result.isError());
        assertEquals("clean", result.content().trim());
    }

    @Test
    void truncatesLargeOutputToTheLimit() {
        ShellTool tool = new ShellTool(workspace);

        ToolResult result = tool.execute(arguments(largeOutputCommand()));

        assertFalse(result.isError());
        assertTrue(result.content().endsWith(TRUNCATION_MARKER));
        assertTrue(result.content().getBytes(StandardCharsets.UTF_8).length <= OUTPUT_LIMIT_BYTES);
    }

    @Test
    void nonZeroExitCodeIsAnErrorAndKeepsOutput() {
        ShellTool tool = new ShellTool(workspace);

        ToolResult result = tool.execute(arguments(failingCommand()));

        assertTrue(result.isError());
        assertFalse(result.content().isBlank());
    }

    @Test
    void invalidArgumentsReturnAnErrorResult() {
        ShellTool tool = new ShellTool(workspace);

        ToolResult result = tool.execute("{}");

        assertTrue(result.isError());
        assertTrue(result.content().startsWith("invalid arguments: "));
    }

    @Test
    void isAlwaysIrreversibleAndSerial() {
        ShellTool tool = new ShellTool(workspace);
        String arguments = arguments("echo hello");

        assertEquals(Reversibility.IRREVERSIBLE, tool.reversibility(arguments));
        assertFalse(tool.parallelizable(arguments));
    }

    private static String arguments(String command) {
        return "{\"cmd\":\""
                + command.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String slowCommand() {
        return isWindows()
                ? "echo started & ping -n 6 127.0.0.1 >nul"
                : "echo started; sleep 5";
    }

    private static String environmentEchoCommand() {
        return isWindows()
                ? "if defined OPENAI_API_KEY (echo leaked) else (echo clean)"
                : "if [ -n \"${OPENAI_API_KEY+x}\" ]; then echo leaked; else echo clean; fi";
    }

    private static String largeOutputCommand() {
        return isWindows()
                ? "for /L %i in (1,1,7000) do @echo xxxxxxxxxx"
                : "head -c 70000 /dev/zero | tr '\\0' x";
    }

    private static String failingCommand() {
        return isWindows() ? "echo failed & exit /b 7" : "echo failed; exit 7";
    }

    private static String mergedOutputCommand() {
        return isWindows()
                ? "echo stdout & echo stderr 1>&2"
                : "echo stdout; echo stderr >&2";
    }
}
