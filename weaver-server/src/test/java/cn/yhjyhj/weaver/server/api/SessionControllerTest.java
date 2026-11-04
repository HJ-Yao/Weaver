package cn.yhjyhj.weaver.server.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.loop.context.Inbox;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
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
    void returnsSessionEventsInSequenceOrder() throws Exception {
        when(sessionService.find("session-1")).thenReturn(Optional.of(SUMMARY));
        when(sessionService.events("session-1")).thenReturn(List.of(
                new StoredEvent(
                        1L, new SessionEvent.MessageAdded(SessionEvent.Role.USER, "hello")),
                new StoredEvent(
                        2L, new SessionEvent.RunFinished(false))));

        mockMvc.perform(get("/api/sessions/session-1/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].type").value("USER_MESSAGE"))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$[1].type").value("RUN_FINISHED"))
                .andExpect(jsonPath("$[1].aborted").value(false));
    }

    @Test
    void returnsNotFoundWhenListingEventsForUnknownSession() throws Exception {
        when(sessionService.find("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sessions/missing/events"))
                .andExpect(status().isNotFound());

        verify(sessionService, never()).events("missing");
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

    @Test
    void rejectsInboxDeliveryWithoutActiveRun() throws Exception {
        when(runService.enqueue("session-1", "queued", Inbox.Target.TURN))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "no active run"));

        mockMvc.perform(post("/api/sessions/session-1/inbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"queued\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptsInboxDeliveryDuringActiveRun() throws Exception {
        when(runService.enqueue("session-1", "steer now", Inbox.Target.STEP))
                .thenReturn(new Inbox.Entry("in-3", Inbox.Target.STEP, "steer now"));

        mockMvc.perform(post("/api/sessions/session-1/inbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"steer now\",\"target\":\"STEP\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("in-3"))
                .andExpect(jsonPath("$.target").value("STEP"))
                .andExpect(jsonPath("$.content").value("steer now"));

        verify(runService).enqueue("session-1", "steer now", Inbox.Target.STEP);
    }

    @Test
    void listsPendingInboxEntries() throws Exception {
        when(runService.pending("session-1")).thenReturn(List.of(
                new Inbox.Entry("in-1", Inbox.Target.STEP, "fix main"),
                new Inbox.Entry("in-2", Inbox.Target.TURN, "then test")));

        mockMvc.perform(get("/api/sessions/session-1/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("in-1"))
                .andExpect(jsonPath("$[0].target").value("STEP"))
                .andExpect(jsonPath("$[0].content").value("fix main"))
                .andExpect(jsonPath("$[1].id").value("in-2"))
                .andExpect(jsonPath("$[1].target").value("TURN"))
                .andExpect(jsonPath("$[1].content").value("then test"));
    }

    @Test
    void listsEmptyInboxWithoutActiveRun() throws Exception {
        when(runService.pending("session-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/session-1/inbox"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void replacesPendingInboxEntry() throws Exception {
        Inbox.Entry updated = new Inbox.Entry("in-1", Inbox.Target.STEP, "only main");
        when(runService.replacePending("session-1", "in-1", "only main"))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/sessions/session-1/inbox/in-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"only main\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("in-1"))
                .andExpect(jsonPath("$.target").value("STEP"))
                .andExpect(jsonPath("$.content").value("only main"));
    }

    @Test
    void returnsNotFoundWhenReplacingUnknownInboxEntry() throws Exception {
        when(runService.replacePending("session-1", "missing", "only main"))
                .thenReturn(null);

        mockMvc.perform(patch("/api/sessions/session-1/inbox/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"only main\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesPendingInboxEntry() throws Exception {
        when(runService.removePending("session-1", "in-1")).thenReturn(true);

        mockMvc.perform(delete("/api/sessions/session-1/inbox/in-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsNotFoundWhenDeletingUnknownInboxEntry() throws Exception {
        when(runService.removePending("session-1", "missing")).thenReturn(false);

        mockMvc.perform(delete("/api/sessions/session-1/inbox/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBlankInboxContent() throws Exception {
        mockMvc.perform(post("/api/sessions/session-1/inbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\" \\t\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/sessions/session-1/inbox/in-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownInboxTarget() throws Exception {
        mockMvc.perform(post("/api/sessions/session-1/inbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"queued\",\"target\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForInboxOnUnknownSession() throws Exception {
        when(runService.enqueue("missing", "queued", Inbox.Target.TURN))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "session not found"));

        mockMvc.perform(post("/api/sessions/missing/inbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"queued\"}"))
                .andExpect(status().isNotFound());
    }
}
