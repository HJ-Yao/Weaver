package cn.yhjyhj.weaver.server.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import cn.yhjyhj.weaver.server.service.SessionService;
import cn.yhjyhj.weaver.server.TestWorkspaceTempDirFactory;
import cn.yhjyhj.weaver.server.workspace.WorkspaceDiffService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceControllerTest {

    @TempDir(factory = TestWorkspaceTempDirFactory.class)
    Path tempDirectory; // 隔离目录浏览与工作区路径

    private WorkspaceDiffService diffService; // 隔离 git 进程
    private SessionService sessions; // 提供会话工作目录
    private Path defaultWorkspace; // 无会话时使用的默认目录
    private MockMvc mockMvc; // 独立控制器 HTTP 入口

    @BeforeEach
    void setUp() throws IOException {
        diffService = Mockito.mock(WorkspaceDiffService.class);
        sessions = Mockito.mock(SessionService.class);
        defaultWorkspace = Files.createDirectory(tempDirectory.resolve("default"));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkspaceController(diffService, sessions, defaultWorkspace))
                .build();
    }

    @Test
    void returnsStructuredWorkspaceDiffForDefaultWorkspace() throws Exception {
        WorkspaceDiff diff = new WorkspaceDiff(
                List.of(new WorkspaceDiff.FileDiff(
                        "core/Example.java",
                        1,
                        1,
                        List.of(
                                new WorkspaceDiff.DiffLine("del", "-old"),
                                new WorkspaceDiff.DiffLine("add", "+new")))),
                true);
        when(diffService.diff(defaultWorkspace)).thenReturn(diff);

        mockMvc.perform(get("/api/workspace/diff"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.files[0].path").value("core/Example.java"))
                .andExpect(jsonPath("$.files[0].additions").value(1))
                .andExpect(jsonPath("$.files[0].deletions").value(1))
                .andExpect(jsonPath("$.files[0].lines[0].kind").value("del"))
                .andExpect(jsonPath("$.files[0].lines[0].text").value("-old"))
                .andExpect(jsonPath("$.files[0].lines[1].kind").value("add"))
                .andExpect(jsonPath("$.files[0].lines[1].text").value("+new"));

        verify(diffService).diff(defaultWorkspace);
    }

    @Test
    void returnsDiffUsingSessionWorkspace() throws Exception {
        Path sessionWorkspace = Files.createDirectory(tempDirectory.resolve("session-workspace"));
        when(sessions.workspace("session-1")).thenReturn(Optional.of(sessionWorkspace));
        when(diffService.diff(sessionWorkspace))
                .thenReturn(new WorkspaceDiff(List.of(), false));

        mockMvc.perform(get("/api/workspace/diff").queryParam("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false));

        verify(diffService).diff(sessionWorkspace);
    }

    @Test
    void fallsBackToDefaultWorkspaceForUnknownSession() throws Exception {
        when(sessions.workspace("missing")).thenReturn(Optional.empty());
        when(diffService.diff(defaultWorkspace))
                .thenReturn(new WorkspaceDiff(List.of(), false));

        mockMvc.perform(get("/api/workspace/diff").queryParam("sessionId", "missing"))
                .andExpect(status().isOk());

        verify(diffService).diff(defaultWorkspace);
    }

    @Test
    void browsesDirectoriesSortedCaseInsensitively() throws Exception {
        Path parent = Files.createDirectory(tempDirectory.resolve("browse-parent"));
        Path root = Files.createDirectory(parent.resolve("root"));
        Files.createDirectory(root.resolve("Beta"));
        Files.createDirectory(root.resolve("alpha"));
        Files.createFile(root.resolve("ignored.txt"));

        mockMvc.perform(get("/api/workspace/browse").queryParam("path", root.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(root.toString()))
                .andExpect(jsonPath("$.parent").value(parent.toString()))
                .andExpect(jsonPath("$.directories.length()").value(2))
                .andExpect(jsonPath("$.directories[0].name").value("alpha"))
                .andExpect(jsonPath("$.directories[0].path").value(
                        root.resolve("alpha").toString()))
                .andExpect(jsonPath("$.directories[1].name").value("Beta"))
                .andExpect(jsonPath("$.roots").isArray());
    }

    @Test
    void browsesUserHomeByDefault() throws Exception {
        mockMvc.perform(get("/api/workspace/browse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(Path.of(System.getProperty("user.home"))
                        .toAbsolutePath()
                        .normalize()
                        .toString()))
                .andExpect(jsonPath("$.roots").isArray());
    }

    @Test
    void rejectsUnknownBrowsePathWithBadRequest() throws Exception {
        Path missing = tempDirectory.resolve("missing");

        mockMvc.perform(get("/api/workspace/browse").queryParam("path", missing.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBrowsePathThatIsAFileWithBadRequest() throws Exception {
        Path file = Files.createFile(tempDirectory.resolve("file.txt"));

        mockMvc.perform(get("/api/workspace/browse").queryParam("path", file.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsInternalServerErrorWhenDiffServiceFails() throws Exception {
        when(diffService.diff(defaultWorkspace))
                .thenThrow(new IllegalStateException("not a git repository"));

        mockMvc.perform(get("/api/workspace/diff"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void returnsInternalServerErrorWhenWorkspaceDirectoryIsUnavailable() throws Exception {
        Path missing = tempDirectory.resolve("missing-workspace");
        MockMvc realServiceMvc = MockMvcBuilders.standaloneSetup(
                        new WorkspaceController(
                                new WorkspaceDiffService(), sessions, missing))
                .build();

        realServiceMvc.perform(get("/api/workspace/diff"))
                .andExpect(status().isInternalServerError());
    }
}
