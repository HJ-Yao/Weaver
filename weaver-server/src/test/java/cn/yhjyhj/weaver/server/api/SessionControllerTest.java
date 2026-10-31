package cn.yhjyhj.weaver.server.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.server.api.dto.SessionDetail;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-28T08:00:00Z"); // 稳定响应时间
    private static final SessionSummary SUMMARY =
            new SessionSummary("session-1", "Planning", CREATED_AT); // 固定会话响应

    @Autowired
    private MockMvc mockMvc; // HTTP 行为入口

    @MockitoBean
    private SessionService sessionService; // 隔离会话存储

    @MockitoBean
    private RunService runService; // 隔离异步运行

    @Test
    void createsSessionAndReturnsCreatedStatus() throws Exception {
        when(sessionService.create("Planning")).thenReturn(SUMMARY);

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Planning\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("session-1"))
                .andExpect(jsonPath("$.title").value("Planning"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-28T08:00:00Z"));
    }

    @Test
    void rejectsBlankSessionTitle() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsSessions() throws Exception {
        when(sessionService.list()).thenReturn(List.of(SUMMARY));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("session-1"))
                .andExpect(jsonPath("$[0].title").value("Planning"));
    }

    @Test
    void returnsSessionDetail() throws Exception {
        SessionSnapshot snapshot = new SessionSnapshot(
                List.of(new SessionSnapshot.Message(
                        cn.yhjyhj.weaver.core.events.SessionEvent.Role.USER, "hello")),
                List.of(),
                List.of(),
                false,
                1L);
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(sessionService.snapshot("session-1")).thenReturn(snapshot);

        mockMvc.perform(get("/api/sessions/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value("session-1"))
                .andExpect(jsonPath("$.snapshot.messages[0].content").value("hello"))
                .andExpect(jsonPath("$.snapshot.lastSeq").value(1));
    }

    @Test
    void returnsNotFoundForUnknownSession() throws Exception {
        when(sessionService.find("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sessions/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesSessionWithNoContentResponse() throws Exception {
        when(sessionService.delete("session-1")).thenReturn(true);

        mockMvc.perform(delete("/api/sessions/session-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsNotFoundWhenDeletingUnknownSession() throws Exception {
        when(sessionService.delete("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/sessions/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendsMessageThroughSseEndpoint() throws Exception {
        when(runService.run("session-1", "hello")).thenReturn(new SseEmitter(0L));

        mockMvc.perform(post("/api/sessions/session-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());

        verify(runService).run("session-1", "hello");
    }

    @Test
    void rejectsBlankMessageContent() throws Exception {
        mockMvc.perform(post("/api/sessions/session-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelsActiveRunWithNoContentResponse() throws Exception {
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(runService.cancel("session-1")).thenReturn(true);

        mockMvc.perform(post("/api/sessions/session-1/run/cancel"))
                .andExpect(status().isNoContent());

        verify(runService).cancel("session-1");
    }

    @Test
    void returnsNotFoundWhenCancellingWithoutActiveRun() throws Exception {
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(runService.cancel("session-1")).thenReturn(false);

        mockMvc.perform(post("/api/sessions/session-1/run/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundWhenCancellingUnknownSession() throws Exception {
        when(sessionService.find("missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/sessions/missing/run/cancel"))
                .andExpect(status().isNotFound());

        verify(runService, never()).cancel("missing");
    }
}
