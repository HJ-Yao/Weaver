package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspacePathsTest {

    @Test
    void parsesRelativePathWithoutAccessingFileSystem() {
        assertEquals(
                Path.of(".git", "config"),
                WorkspacePaths.parseRelative("nested\\..\\.git\\config").orElseThrow());
    }

    @Test
    void rejectsNullOrEscapingPaths() {
        assertTrue(WorkspacePaths.parseRelative(null).isEmpty());
        assertTrue(WorkspacePaths.parseRelative("../.git/config").isEmpty());
        assertTrue(WorkspacePaths.parseRelative("/etc/passwd").isEmpty());
        assertTrue(WorkspacePaths.parseRelative("C:\\workspace\\.git\\config").isEmpty());
        assertTrue(WorkspacePaths.parseRelative("bad\u0000path").isEmpty());
    }
}
