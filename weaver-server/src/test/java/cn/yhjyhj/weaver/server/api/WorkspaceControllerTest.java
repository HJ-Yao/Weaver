package cn.yhjyhj.weaver.server.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import cn.yhjyhj.weaver.server.workspace.WorkspaceDiffService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceControllerTest {

    private WorkspaceDiffService service; // 隔离 git 进程
    private MockMvc mockMvc; // 独立控制器 HTTP 入口

    @BeforeEach
    void setUp() {
        service = Mockito.mock(WorkspaceDiffService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(service)).build();
    }

    @Test
    void returnsStructuredWorkspaceDiff() throws Exception {
        WorkspaceDiff diff = new WorkspaceDiff(
                List.of(new WorkspaceDiff.FileDiff(
                        "core/Example.java",
                        1,
                        1,
                        List.of(
                                new WorkspaceDiff.DiffLine("del", "-old"),
                                new WorkspaceDiff.DiffLine("add", "+new")))),
                true);
        when(service.diff()).thenReturn(diff);

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
    }

    @Test
    void returnsInternalServerErrorWhenDiffServiceFails() throws Exception {
        when(service.diff()).thenThrow(new IllegalStateException("not a git repository"));

        mockMvc.perform(get("/api/workspace/diff"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void returnsInternalServerErrorWhenWorkspaceIsNotGitRepository() throws Exception {
        Path workspace = Path.of(System.getProperty("java.io.tmpdir"));
        MockMvc realServiceMvc = MockMvcBuilders.standaloneSetup(
                        new WorkspaceController(new WorkspaceDiffService(workspace)))
                .build();

        realServiceMvc.perform(get("/api/workspace/diff"))
                .andExpect(status().isInternalServerError());
    }
}
