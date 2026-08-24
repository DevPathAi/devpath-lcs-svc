package ai.devpath.lcs.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.lcs.config.SecurityConfig;
import ai.devpath.lcs.release.ReleasePreviewRegistry;
import ai.devpath.lcs.service.LcsService;
import ai.devpath.shared.error.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LcsController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
class LcsControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean LcsService lcsService;
  @MockitoBean ReleasePreviewRegistry releasePreview;

  private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String sub) {
    return jwt().jwt(j -> j.subject(sub));
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mvc.perform(get("/lcs/preferences")).andExpect(status().isUnauthorized());
  }

  @Test
  void draftReturns200() throws Exception {
    when(lcsService.draft(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new DraftResponse("snap_x", Instant.now().plusSeconds(600),
            Map.of(), List.of("current_content"), List.of()));

    mvc.perform(post("/lcs/snapshots/draft").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\"question_attachment\",\"contentId\":10,\"requestedFields\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftId").value("snap_x"));
  }

  @Test
  void mentorDraftBindsTheReleaseHeadersOnlyAfterAssemblySucceeds() throws Exception {
    DraftResponse response = new DraftResponse(
        "snap_12345678-1234-4123-8123-123456789abc",
        Instant.now().plusSeconds(600), Map.of(), List.of(), List.of());
    when(lcsService.draft(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);
    String candidate = "a".repeat(64);
    String run = "R".repeat(43);

    mvc.perform(post("/lcs/snapshots/draft").with(user("42"))
            .header("X-Candidate-Spec-Sha256", candidate)
            .header("X-Release-Run-Key", run)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\"mentor_prompt\",\"requestedFields\":[]}"))
        .andExpect(status().isOk());

    verify(releasePreview).record(
        eq(candidate), eq(run), eq(42L), org.mockito.ArgumentMatchers.any(), eq(response));
  }

  @Test
  void draftRejectsUnknownTopLevelFieldsBeforeService() throws Exception {
    mvc.perform(post("/lcs/snapshots/draft").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"purpose\":\"mentor_prompt\",\"contentId\":10,"
                + "\"requestedFields\":[],\"unknown\":\"must fail\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    verify(lcsService, never()).draft(anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void commitReturns201() throws Exception {
    when(lcsService.commit(anyLong(), eq("snap_x"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CommitResponse(99L, "committed", true));

    mvc.perform(post("/lcs/snapshots/snap_x/commit").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"attachedToType\":\"question\",\"attachedToId\":5,\"visibility\":\"answerers_only\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.snapshotId").value(99))
        .andExpect(jsonPath("$.immutable").value(true));
  }

  @Test
  void getSnapshotReturns200() throws Exception {
    when(lcsService.getSnapshot(anyLong(), eq(5L)))
        .thenReturn(new SnapshotView(5L, Instant.now(), Map.of(), "answerer"));

    mvc.perform(get("/lcs/snapshots/5").with(user("42")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5))
        .andExpect(jsonPath("$.renderedFor").value("answerer"));
  }

  @Test
  void mentorConsumeReturnsOnlyTheStrictSnapshotEnvelope() throws Exception {
    when(lcsService.consumeMentorSnapshot(42L, 5L))
        .thenReturn(new MentorSnapshotView(5L, "mentor_prompt", "private",
            List.of("current_content"), Map.of("current_content", Map.of("contentId", 10L))));

    mvc.perform(get("/lcs/mentor/snapshots/5").with(user("42")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshotId").value(5))
        .andExpect(jsonPath("$.purpose").value("mentor_prompt"))
        .andExpect(jsonPath("$.visibility").value("private"))
        .andExpect(jsonPath("$.fieldsIncluded[0]").value("current_content"))
        .andExpect(jsonPath("$.content.current_content.contentId").value(10))
        .andExpect(jsonPath("$.createdAt").doesNotExist())
        .andExpect(jsonPath("$.renderedFor").doesNotExist());
  }

  @Test
  void mentorConsumeDenialDoesNotExposeSnapshotIdentityOrContent() throws Exception {
    when(lcsService.consumeMentorSnapshot(42L, 9L))
        .thenThrow(new ai.devpath.lcs.config.NotFoundException("mentor snapshot unavailable"));

    mvc.perform(get("/lcs/mentor/snapshots/9").with(user("42")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("mentor snapshot unavailable"))
        .andExpect(jsonPath("$.snapshotId").doesNotExist())
        .andExpect(jsonPath("$.content").doesNotExist());
  }

  @Test
  void byQuestionReturns200() throws Exception {
    when(lcsService.getSnapshotByQuestion(anyLong(), eq(5L)))
        .thenReturn(new SnapshotView(7L, Instant.now(), Map.of(), "answerer"));

    mvc.perform(get("/lcs/snapshots/by-question/5").with(user("42")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.renderedFor").value("answerer"));
  }

  @Test
  void byQuestionMissingReturns404() throws Exception {
    when(lcsService.getSnapshotByQuestion(anyLong(), eq(404L)))
        .thenThrow(new ai.devpath.lcs.config.NotFoundException("no snapshot for question: 404"));

    mvc.perform(get("/lcs/snapshots/by-question/404").with(user("42")))
        .andExpect(status().isNotFound())
        // 스펙 §3.4 공통 에러 envelope(공용 ApiExceptionHandler).
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void forbiddenReturnsEnvelope() throws Exception {
    when(lcsService.getSnapshot(anyLong(), eq(9L)))
        .thenThrow(new ai.devpath.lcs.config.ForbiddenException("not owner"));

    mvc.perform(get("/lcs/snapshots/9").with(user("42")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  void getPreferencesReturns200() throws Exception {
    when(lcsService.getPreferences(anyLong()))
        .thenReturn(new PreferencesView(true, true, true, false, true, true, "answerers_only"));

    mvc.perform(get("/lcs/preferences").with(user("42")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.collectRecentErrors").value(false))
        .andExpect(jsonPath("$.defaultVisibility").value("answerers_only"));
  }

  @Test
  void putPreferencesReturns200() throws Exception {
    when(lcsService.putPreferences(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new PreferencesView(false, true, false, true, false, true, "private"));

    mvc.perform(put("/lcs/preferences").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"collectCurrentContent\":false,\"collectLearningPath\":true,"
                + "\"collectActiveTags\":false,\"collectRecentErrors\":true,"
                + "\"collectTagReputation\":false,\"collectLevel\":true,"
                + "\"defaultVisibility\":\"private\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultVisibility").value("private"));
  }
}
