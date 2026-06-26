package ai.devpath.lcs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.lcs.api.FieldUnavailable;
import ai.devpath.lcs.client.ContentView;
import ai.devpath.lcs.client.LearningClient;
import ai.devpath.lcs.client.RunView;
import ai.devpath.lcs.client.SandboxClient;
import ai.devpath.lcs.domain.UserContextPreference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotAssemblerTest {

  private final LearningClient learning = mock(LearningClient.class);
  private final SandboxClient sandbox = mock(SandboxClient.class);
  private final SnapshotAssembler assembler = new SnapshotAssembler(learning, sandbox);

  private static UserContextPreference prefs() {
    return new UserContextPreference(1L); // defaults: collectCurrentContent=true
  }

  private static String reasonFor(List<FieldUnavailable> list, String field) {
    return list.stream().filter(f -> f.field().equals(field)).map(FieldUnavailable::reason)
        .findFirst().orElse(null);
  }

  @Test
  void currentContentPresentWhenFoundAndPrefOn() {
    when(learning.getContent(10L))
        .thenReturn(Optional.of(new ContentView(10L, "slug", "JPA 기초", "java", "body")));
    when(sandbox.recentByUser(anyLong(), anyInt())).thenReturn(List.of());

    AssemblyResult r = assembler.assemble(1L, 10L, null, prefs());

    assertTrue(r.fieldsIncluded().contains("current_content"));
    @SuppressWarnings("unchecked")
    Map<String, Object> cc = (Map<String, Object>) r.content().get("current_content");
    assertEquals(10L, cc.get("contentId"));
    assertEquals("JPA 기초", cc.get("title"));
    assertEquals("java", cc.get("track"));
    // Phase 2 deferred fields
    assertEquals("phase2_deferred", reasonFor(r.fieldsUnavailable(), "active_tags"));
    assertEquals("phase2_deferred", reasonFor(r.fieldsUnavailable(), "tag_reputation"));
    assertEquals("phase2_deferred", reasonFor(r.fieldsUnavailable(), "current_path"));
    assertEquals("phase2_deferred", reasonFor(r.fieldsUnavailable(), "recent_errors"));
  }

  @Test
  void recentActivityPresentWhenPrefOn() {
    when(learning.getContent(anyLong())).thenReturn(Optional.empty());
    when(sandbox.recentByUser(1L, 5))
        .thenReturn(List.of(new RunView(7L, 1L, "python", null, "code", "out", "", 0, "SUCCESS")));

    AssemblyResult r = assembler.assemble(1L, null, null, prefs());

    assertTrue(r.fieldsIncluded().contains("recent_activity"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runs = (List<Map<String, Object>>) r.content().get("recent_activity");
    assertEquals(1, runs.size());
    assertEquals("python", runs.get(0).get("language"));
    assertEquals("SUCCESS", runs.get(0).get("status"));
  }

  @Test
  void currentContentUnavailableWhenNoContentId() {
    when(sandbox.recentByUser(anyLong(), anyInt())).thenReturn(List.of());

    AssemblyResult r = assembler.assemble(1L, null, null, prefs());

    assertFalse(r.fieldsIncluded().contains("current_content"));
    assertEquals("no_content_context", reasonFor(r.fieldsUnavailable(), "current_content"));
  }

  @Test
  void currentContentUnavailableWhenPrefOff() {
    UserContextPreference p = prefs();
    p.setCollectCurrentContent(false);

    AssemblyResult r = assembler.assemble(1L, 10L, null, p);

    assertEquals("user_preference_off", reasonFor(r.fieldsUnavailable(), "current_content"));
    assertEquals("user_preference_off", reasonFor(r.fieldsUnavailable(), "recent_activity"));
    assertFalse(r.fieldsIncluded().contains("current_content"));
    assertFalse(r.fieldsIncluded().contains("recent_activity"));
  }

  @Test
  void currentContentUnavailableWhenClientEmptyGraceful() {
    when(learning.getContent(10L)).thenReturn(Optional.empty());
    when(sandbox.recentByUser(anyLong(), anyInt())).thenReturn(List.of());

    AssemblyResult r = assembler.assemble(1L, 10L, null, prefs());

    assertEquals("source_unavailable", reasonFor(r.fieldsUnavailable(), "current_content"));
  }

  @Test
  void requestedFieldsFilterRestrictsToRequested() {
    when(learning.getContent(10L))
        .thenReturn(Optional.of(new ContentView(10L, "slug", "t", "java", "b")));

    AssemblyResult r = assembler.assemble(1L, 10L, List.of("current_content"), prefs());

    assertTrue(r.fieldsIncluded().contains("current_content"));
    // recent_activity not requested → neither included nor reported unavailable
    assertFalse(r.fieldsIncluded().contains("recent_activity"));
    assertEquals(null, reasonFor(r.fieldsUnavailable(), "recent_activity"));
    assertEquals(null, reasonFor(r.fieldsUnavailable(), "active_tags"));
  }
}
