package cn.yhjyhj.weaver.server.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    private final UnifiedDiffParser parser = new UnifiedDiffParser(); // 无状态解析器可安全复用

    @Test
    void parsesModifiedFilePathCountsAndLineKinds() {
        String diff = """
                diff --git a/core/Example.java b/core/Example.java
                index 1234567..89abcde 100644
                --- a/core/Example.java
                +++ b/core/Example.java
                @@ -1,2 +1,2 @@
                -old
                +new
                 unchanged
                \\ No newline at end of file
                """;

        WorkspaceDiff result = parser.parse(diff);

        assertFalse(result.truncated());
        assertEquals(1, result.files().size());
        WorkspaceDiff.FileDiff file = result.files().getFirst();
        assertEquals("core/Example.java", file.path());
        assertEquals(1, file.additions());
        assertEquals(1, file.deletions());
        assertEquals(
                List.of(
                        new WorkspaceDiff.DiffLine("del", "-old"),
                        new WorkspaceDiff.DiffLine("add", "+new"),
                        new WorkspaceDiff.DiffLine("context", " unchanged")),
                file.lines());
    }

    @Test
    void resolvesNewAndDeletedFilePathsFromEitherSideOfDevNull() {
        String diff = """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1 @@
                +created
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                --- a/old.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -removed
                """;

        WorkspaceDiff result = parser.parse(diff);

        assertEquals(List.of("new.txt", "old.txt"), paths(result));
        assertEquals(1, result.files().getFirst().additions());
        assertEquals(1, result.files().getLast().deletions());
    }

    @Test
    void preservesMultipleFileOrder() {
        String diff = """
                diff --git a/first.txt b/first.txt
                --- a/first.txt
                +++ b/first.txt
                @@ -0,0 +1 @@
                +first
                diff --git a/second.txt b/second.txt
                --- a/second.txt
                +++ b/second.txt
                @@ -1 +1 @@
                -old
                +second
                """;

        WorkspaceDiff result = parser.parse(diff);

        assertEquals(List.of("first.txt", "second.txt"), paths(result));
        assertEquals(1, result.files().getFirst().additions());
        assertEquals(1, result.files().getLast().additions());
        assertEquals(1, result.files().getLast().deletions());
    }

    @Test
    void fallsBackToDiffHeaderForBinaryFileWithoutHunks() {
        String diff = """
                diff --git a/assets/logo.png b/assets/logo.png
                index 1234567..89abcde 100644
                Binary files a/assets/logo.png and b/assets/logo.png differ
                """;

        WorkspaceDiff result = parser.parse(diff);

        assertEquals(1, result.files().size());
        WorkspaceDiff.FileDiff file = result.files().getFirst();
        assertEquals("assets/logo.png", file.path());
        assertTrue(file.lines().isEmpty());
        assertEquals(0, file.additions());
        assertEquals(0, file.deletions());
    }

    @Test
    void keepsHunkLinesWhoseContentStartsWithPlusOrMinus() {
        String diff = """
                diff --git a/Counter.java b/Counter.java
                --- a/Counter.java
                +++ b/Counter.java
                @@ -1,2 +1,2 @@
                ---i;
                +++i;
                 return i;
                """;

        WorkspaceDiff result = parser.parse(diff);

        WorkspaceDiff.FileDiff file = result.files().getFirst();
        assertEquals(1, file.additions());
        assertEquals(1, file.deletions());
        assertEquals(
                List.of(
                        new WorkspaceDiff.DiffLine("del", "---i;"),
                        new WorkspaceDiff.DiffLine("add", "+++i;"),
                        new WorkspaceDiff.DiffLine("context", " return i;")),
                file.lines());
    }

    @Test
    void returnsEmptyResultForEmptyOutput() {
        WorkspaceDiff result = parser.parse("");

        assertTrue(result.files().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void truncatesAtFiftyFiles() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 51; index++) {
            appendFile(diff, "file-" + index + ".txt", 1);
        }

        WorkspaceDiff result = parser.parse(diff.toString());

        assertEquals(50, result.files().size());
        assertEquals("file-49.txt", result.files().getLast().path());
        assertTrue(result.truncated());
    }

    @Test
    void truncatesAtFiveHundredLinesPerFile() {
        StringBuilder diff = new StringBuilder();
        appendFile(diff, "large.txt", 501);

        WorkspaceDiff result = parser.parse(diff.toString());

        assertEquals(1, result.files().size());
        assertEquals(500, result.files().getFirst().lines().size());
        assertEquals(500, result.files().getFirst().additions());
        assertTrue(result.truncated());
    }

    @Test
    void truncatesAtTwoThousandTotalLines() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            appendFile(diff, "file-" + index + ".txt", 500);
        }
        appendFile(diff, "file-4.txt", 1);

        WorkspaceDiff result = parser.parse(diff.toString());

        assertEquals(
                2000,
                result.files().stream().mapToInt(file -> file.lines().size()).sum());
        assertEquals(5, result.files().size());
        assertTrue(result.truncated());
    }

    private static List<String> paths(WorkspaceDiff result) {
        return result.files().stream().map(WorkspaceDiff.FileDiff::path).toList();
    }

    private static void appendFile(StringBuilder diff, String path, int addedLines) {
        diff.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        diff.append("new file mode 100644\n");
        diff.append("--- /dev/null\n");
        diff.append("+++ b/").append(path).append('\n');
        diff.append("@@ -0,0 +1,").append(addedLines).append(" @@\n");
        for (int line = 0; line < addedLines; line++) {
            diff.append('+').append("line ").append(line).append('\n');
        }
    }
}
