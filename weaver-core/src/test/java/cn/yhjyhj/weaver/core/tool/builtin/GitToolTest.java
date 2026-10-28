package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.WorkspaceTempDirFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolTest {

    @TempDir(factory = WorkspaceTempDirFactory.class)
    Path workspace; // 每个测试独立的临时 Git 工作区

    @Test
    void readsStatusDiffAndLogFromTemporaryRepository() throws Exception {
        initializeRepository();
        Files.writeString(workspace.resolve("tracked.txt"), "before\n");
        assertEquals(0, runGit("add", "tracked.txt"));
        assertEquals(0, runGit("commit", "-m", "initial"));
        Files.writeString(workspace.resolve("tracked.txt"), "after\n");
        Files.writeString(workspace.resolve("untracked.txt"), "new\n");
        GitTool tool = new GitTool(workspace);

        ToolResult status = tool.execute("{\"op\":\"status\"}");
        ToolResult diff = tool.execute("{\"op\":\"diff\",\"path\":\"tracked.txt\"}");
        ToolResult log = tool.execute("{\"op\":\"log\",\"limit\":10}");

        assertFalse(status.isError(), status.content());
        assertTrue(status.content().contains("tracked.txt"));
        assertFalse(diff.isError(), diff.content());
        assertTrue(diff.content().contains("-before"));
        assertTrue(diff.content().contains("+after"));
        assertFalse(log.isError(), log.content());
        assertTrue(log.content().contains("initial"));
    }

    @Test
    void commitCanBeReadBackFromLog() throws Exception {
        initializeRepository();
        Files.writeString(workspace.resolve("file.txt"), "content\n");
        assertEquals(0, runGit("add", "file.txt"));
        GitTool tool = new GitTool(workspace);

        ToolResult commit = tool.execute(
                "{\"op\":\"commit\",\"message\":\"tool commit\"}");
        ToolResult log = tool.execute("{\"op\":\"log\",\"limit\":5}");

        assertFalse(commit.isError(), commit.content());
        assertFalse(log.isError(), log.content());
        assertTrue(log.content().contains("tool commit"));
    }

    @Test
    void rejectsBlankCommitMessage() {
        GitTool tool = new GitTool(workspace);

        ToolResult result = tool.execute("{\"op\":\"commit\",\"message\":\"  \"}");

        assertTrue(result.isError());
        assertTrue(result.content().startsWith("invalid arguments: "));
    }

    @Test
    void rejectsDiffPathsThatEscapeTheWorkspace() {
        ToolResult result = new GitTool(workspace)
                .execute("{\"op\":\"diff\",\"path\":\"../../etc/passwd\"}");

        assertTrue(result.isError());
        assertEquals("path escapes workspace", result.content());
    }

    @Test
    void pushesHeadToOriginWithoutForce() {
        List<String> command = GitTool.pushCommand();

        assertEquals(List.of("git", "push", "origin", "HEAD"), command);
        assertFalse(command.contains("--force"));
    }

    @Test
    void rejectsLogLimitsAboveOneThousand() {
        ToolResult result = new GitTool(workspace)
                .execute("{\"op\":\"log\",\"limit\":1001}");

        assertTrue(result.isError());
        assertEquals("limit must be <= 1000", result.content());
    }

    @Test
    void rejectsInvalidOperationsAndLogLimits() {
        GitTool tool = new GitTool(workspace);
        List<String> invalidArguments = List.of(
                "{\"op\":\"unknown\"}",
                "{\"op\":\"\"}",
                "{\"op\":\"log\",\"limit\":\"10\"}",
                "{\"op\":\"log\",\"limit\":0}",
                "{\"op\":\"diff\",\"path\":42}",
                "{\"op\":\"commit\"}");

        for (String arguments : invalidArguments) {
            ToolResult result = tool.execute(arguments);

            assertTrue(result.isError(), arguments);
            assertTrue(result.content().startsWith("invalid arguments: "), arguments);
        }
    }

    @Test
    void invalidOperationDeclarationsUseConservativeFallbacks() {
        GitTool tool = new GitTool(workspace);
        String arguments = "{\"op\":\"unknown\"}";

        assertEquals(Reversibility.IRREVERSIBLE, tool.reversibility(arguments));
        assertFalse(tool.parallelizable(arguments));
    }

    @Test
    void declaresReadOperationsAsReversibleAndWritesAsIrreversible() {
        GitTool tool = new GitTool(workspace);
        String status = "{\"op\":\"status\"}";
        String diff = "{\"op\":\"diff\"}";
        String log = "{\"op\":\"log\",\"limit\":10}";
        String commit = "{\"op\":\"commit\",\"message\":\"message\"}";
        String push = "{\"op\":\"push\"}";

        for (String read : new String[] {status, diff, log}) {
            assertEquals(Reversibility.REVERSIBLE, tool.reversibility(read));
            assertTrue(tool.parallelizable(read));
        }
        for (String write : new String[] {commit, push}) {
            assertEquals(Reversibility.IRREVERSIBLE, tool.reversibility(write));
            assertFalse(tool.parallelizable(write));
        }
    }

    private void initializeRepository() throws Exception {
        assertEquals(0, runGit("init", "-q"));
        assertEquals(0, runGit("config", "user.name", "Weaver Test"));
        assertEquals(0, runGit("config", "user.email", "weaver@example.test"));
    }

    private int runGit(String... arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(concat("git", arguments));
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            fail("git command failed: " + output);
        }
        return exitCode;
    }

    private static String[] concat(String first, String... rest) {
        String[] result = new String[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
