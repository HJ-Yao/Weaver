package cn.yhjyhj.weaver.server.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.SessionService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

    private static final SessionSummary SUMMARY = // 已存在会话的固定元数据
            new SessionSummary("session-1", "Planning", Instant.parse("2026-10-28T08:00:00Z"));

    @Autowired
    private MockMvc mockMvc; // HTTP 行为入口

    @MockitoBean
    private SessionService sessionService; // 隔离会话存在性检查

    @MockitoBean
    private RunService runService; // 隔离活动审批闸门

    @Test
    void resolvesApprovalWithNoContentResponse() throws Exception {
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(runService.resolveApproval("session-1", "call-1", false)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/session-1/approvals/call-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false}"))
                .andExpect(status().isNoContent());

        verify(runService).resolveApproval("session-1", "call-1", false);
    }

    @Test
    void returnsNotFoundWhenApprovalIsNotPending() throws Exception {
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(runService.resolveApproval("session-1", "missing", true)).thenReturn(false);

        mockMvc.perform(post("/api/sessions/session-1/approvals/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(sessionService.find("missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/sessions/missing/approvals/call-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(runService);
    }

    @Test
    void rejectsMissingApprovedField() throws Exception {
        mockMvc.perform(post("/api/sessions/session-1/approvals/call-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":null}"))
                .andExpect(status().isBadRequest());
    }
}
