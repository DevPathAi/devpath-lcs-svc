package ai.devpath.lcs.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.lcs.config.SecurityConfig;
import ai.devpath.lcs.service.LcsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LcsController.class)
@Import(SecurityConfig.class)
class LcsControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean LcsService lcsService;

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
        .andExpect(status().isNotFound());
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
