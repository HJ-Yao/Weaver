package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.WorkspaceTempDirFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolTest {

    @TempDir(factory = WorkspaceTempDirFactory.class)
    Path workspace; // 每个测试独立的临时工作区

    @Test
    void readsWritesAndListsFiles() throws Exception {
        FileTool tool = new FileTool(workspace);

        ToolResult write = tool.execute(
                arguments("write", "notes/hello.txt", "hello"));
        assertFalse(write.isError());
        assertEquals("wrote 5 bytes", write.content());
        assertEquals("hello", Files.readString(workspace.resolve("notes/hello.txt")));

        ToolResult read = tool.execute(arguments("read", "notes/hello.txt", null));
        assertFalse(read.isError());
        assertEquals("hello", read.content());

        ToolResult list = tool.execute(arguments("list", ".", null));
        assertFalse(list.isError());
        assertEquals(List.of("notes"), list.content().lines().toList());
    }

    @Test
    void rejectsPathsThatEscapeTheWorkspace() {
        FileTool tool = new FileTool(workspace);
        String absolutePath = workspace.resolve("outside.txt").toString();

        for (String path : List.of("../outside.txt", absolutePath, "a/../../outside")) {
            ToolResult result = tool.execute(arguments("read", path, null));

            assertTrue(result.isError(), path);
            assertEquals("path escapes workspace", result.content(), path);
        }
    }

    @Test
    void writeCannotEscapeTheWorkspace() {
        FileTool tool = new FileTool(workspace);

        ToolResult result = tool.execute(arguments("write", "../outside.txt", "bad"));

        assertTrue(result.isError());
        assertEquals("path escapes workspace", result.content());
        assertFalse(Files.exists(workspace.getParent().resolve("outside.txt")));
    }

    @Test
    void rejectsReadsThroughSymbolicLinkToOutsideWorkspace() throws Exception {
        Path outside = Files.createTempDirectory(workspace.getParent(), "weaver-outside-");
        Path link = workspace.resolve("link");
        try {
            createSymbolicLinkOrSkip(link, outside);

            ToolResult result = new FileTool(workspace)
                    .execute(arguments("read", "link/outside.txt", null));

            assertTrue(result.isError());
            assertEquals("path escapes workspace", result.content());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsWritesThroughSymbolicLinkToOutsideWorkspace() throws Exception {
        Path outside = Files.createTempDirectory(workspace.getParent(), "weaver-outside-");
        Path link = workspace.resolve("link");
        try {
            createSymbolicLinkOrSkip(link, outside);

            ToolResult result = new FileTool(workspace)
                    .execute(arguments("write", "link/outside.txt", "bad"));

            assertTrue(result.isError());
            assertEquals("path escapes workspace", result.content());
            assertFalse(Files.exists(outside.resolve("outside.txt")));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymbolicLinkTargetEvenWhenItPointsInsideWorkspace() throws Exception {
        Path target = Files.writeString(workspace.resolve("target.txt"), "inside");
        Path link = workspace.resolve("link.txt");
        try {
            createSymbolicLinkOrSkip(link, target);

            ToolResult result = new FileTool(workspace)
                    .execute(arguments("read", "link.txt", null));

            assertTrue(result.isError());
            assertEquals("path escapes workspace", result.content());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void rejectsWritesThroughSymbolicLinkEvenWhenItPointsInsideWorkspace() throws Exception {
        Path targetDirectory = Files.createDirectory(workspace.resolve("target-directory"));
        Path link = workspace.resolve("link-directory");
        try {
            createSymbolicLinkOrSkip(link, targetDirectory);

            ToolResult result = new FileTool(workspace)
                    .execute(arguments("write", "link-directory/new.txt", "bad"));

            assertTrue(result.isError());
            assertEquals("path escapes workspace", result.content());
            assertFalse(Files.exists(targetDirectory.resolve("new.txt")));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void rejectsReadsLargerThanTheConfiguredLimit() throws Exception {
        byte[] content = new byte[(int) FileTool.MAX_READ_BYTES + 1];
        Files.write(workspace.resolve("large.txt"), content);

        ToolResult result = new FileTool(workspace)
                .execute(arguments("read", "large.txt", null));

        assertTrue(result.isError());
        assertEquals("file too large: 1048577 bytes", result.content());
    }

    @Test
    void rejectsWritesLargerThanTheConfiguredLimitWithoutWriting() {
        String content = "x".repeat(FileTool.MAX_WRITE_BYTES + 1);

        ToolResult result = new FileTool(workspace)
                .execute(arguments("write", "large.txt", content));

        assertTrue(result.isError());
        assertEquals("content too large", result.content());
        assertFalse(Files.exists(workspace.resolve("large.txt")));
    }

    @Test
    void truncatesListAtItsConfiguredEntryLimit() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        Files.writeString(workspace.resolve("c.txt"), "c");

        ToolResult result = new FileTool(workspace, 2)
                .execute(arguments("list", ".", null));

        assertFalse(result.isError());
        assertEquals(
                List.of("a.txt", "b.txt", "...[truncated]"),
                result.content().lines().toList());
    }

    @Test
    void missingFileIsAnError() {
        FileTool tool = new FileTool(workspace);

        ToolResult result = tool.execute(arguments("read", "missing.txt", null));

        assertTrue(result.isError());
    }

    @Test
    void invalidArgumentsReturnAnErrorResult() {
        FileTool tool = new FileTool(workspace);

        ToolResult result = tool.execute("{not-json");

        assertTrue(result.isError());
        assertTrue(result.content().startsWith("invalid arguments: "));
    }

    @Test
    void declaresReversibilityAndParallelismByOperation() {
        FileTool tool = new FileTool(workspace);

        String read = arguments("read", "file.txt", null);
        String write = arguments("write", "file.txt", "value");
        String list = arguments("list", ".", null);

        assertEquals(Reversibility.REVERSIBLE, tool.reversibility(read));
        assertTrue(tool.parallelizable(read));
        assertEquals(Reversibility.RECOVERABLE, tool.reversibility(write));
        assertFalse(tool.parallelizable(write));
        assertEquals(Reversibility.REVERSIBLE, tool.reversibility(list));
        assertTrue(tool.parallelizable(list));
    }

    private static String arguments(String op, String path, String content) {
        StringBuilder json = new StringBuilder("{\"op\":\"")
                .append(escape(op))
                .append("\",\"path\":\"")
                .append(escape(path))
                .append('"');
        if (content != null) {
            json.append(",\"content\":\"").append(escape(content)).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false, "symbolic links unavailable: " + exception.getMessage());
        }
    }
}
