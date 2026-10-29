package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import org.junit.jupiter.api.Test;

class HardDenyPolicyTest {

    private final HardDenyPolicy policy = new HardDenyPolicy();

    @Test
    void deniesFileWritesInsideProtectedDirectories() {
        assertTrue(policy.isDenied(call("file", """
                {"op":"write","path":".git/config"}
                """)));
        assertTrue(policy.isDenied(call("file", """
                {"op":"write","path":"nested/.weaver/state.db"}
                """)));
        assertTrue(policy.isDenied(call("file", """
                {"op":"write","path":"src/../.git/config"}
                """)));
    }

    @Test
    void allowsFileReadsAndWritesOutsideProtectedDirectories() {
        assertFalse(policy.isDenied(call("file", """
                {"op":"read","path":".git/config"}
                """)));
        assertFalse(policy.isDenied(call("file", """
                {"op":"list","path":".weaver"}
                """)));
        assertFalse(policy.isDenied(call("file", """
                {"op":"write","path":".gitignore"}
                """)));
        assertFalse(policy.isDenied(call("file", """
                {"op":"write","path":"src/Main.java"}
                """)));
    }

    @Test
    void deniesShellDestructiveIntentAgainstProtectedDirectories() {
        assertTrue(policy.isDenied(call("shell", """
                {"cmd":"rm -rf .git"}
                """)));
        assertTrue(policy.isDenied(call("shell", """
                {"cmd":"MOVE .weaver archive"}
                """)));
        assertTrue(policy.isDenied(call("shell", """
                {"cmd":"python -c \\\"shutil.rmtree('.git')\\\""}
                """)));
        assertTrue(policy.isDenied(call("shell", """
                {"cmd":"git rm --cached .weaver/state.db"}
                """)));
    }

    @Test
    void allowsNonDestructiveOrUnprotectedShellCommands() {
        assertFalse(policy.isDenied(call("shell", """
                {"cmd":"echo .git"}
                """)));
        assertFalse(policy.isDenied(call("shell", """
                {"cmd":"rm -rf build"}
                """)));
        assertFalse(policy.isDenied(call("shell", """
                {"cmd":"rm -rf .gitignore"}
                """)));
    }

    @Test
    void ignoresGitAndMalformedArguments() {
        assertFalse(policy.isDenied(call("git", """
                {"op":"commit","message":"change"}
                """)));
        assertFalse(policy.isDenied(call("shell", "{")));
        assertFalse(policy.isDenied(call("file", """
                {"op":"write"}
                """)));
    }

    private static StreamChunk.ToolCall call(String toolName, String argumentsJson) {
        return new StreamChunk.ToolCall("call-1", toolName, argumentsJson);
    }
}
