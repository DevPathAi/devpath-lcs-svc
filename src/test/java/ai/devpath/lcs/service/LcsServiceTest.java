package ai.devpath.lcs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.CommitResponse;
import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import ai.devpath.lcs.api.PreferencesView;
import ai.devpath.lcs.api.SnapshotView;
import ai.devpath.lcs.config.ForbiddenException;
import ai.devpath.lcs.config.NotFoundException;
import ai.devpath.lcs.domain.LearningContextSnapshot;
import ai.devpath.lcs.domain.LearningContextSnapshotRepository;
import ai.devpath.lcs.domain.UserContextPreferenceRepository;
import ai.devpath.lcs.draft.Draft;
import ai.devpath.lcs.draft.DraftStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LcsServiceTest {

  private final SnapshotAssembler assembler = mock(SnapshotAssembler.class);
  private final DraftStore draftStore = mock(DraftStore.class);
  private final LearningContextSnapshotRepository snapshots =
      mock(LearningContextSnapshotRepository.class);
  private final UserContextPreferenceRepository preferences =
      mock(UserContextPreferenceRepository.class);
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private final LcsService service =
      new LcsService(assembler, draftStore, snapshots, preferences, jsonMapper);

  private LearningContextSnapshot mockEntity(long id, long userId, String visibility, String json) {
    LearningContextSnapshot e = mock(LearningContextSnapshot.class);
    when(e.getId()).thenReturn(id);
    when(e.getUserId()).thenReturn(userId);
    when(e.getVisibility()).thenReturn(visibility);
    when(e.getContentSnapshot()).thenReturn(json);
    when(e.getCreatedAt()).thenReturn(Instant.now());
    return e;
  }

  @Test
  void draftAssemblesAndStores() {
    when(preferences.findById(1L)).thenReturn(Optional.empty());
    when(assembler.assemble(anyLong(), any(), any(), any()))
        .thenReturn(new AssemblyResult(Map.of("current_content", Map.of("title", "t")),
            List.of("current_content"), List.of()));
    when(draftStore.save(anyLong(), any(), anyList())).thenReturn("snap_abc");

    DraftResponse res = service.draft(1L, new DraftRequest("question_attachment", 10L, null));

    assertEquals("snap_abc", res.draftId());
    assertTrue(res.fieldsAvailable().contains("current_content"));
    assertTrue(res.expiresAt().isAfter(Instant.now()));
    verify(draftStore).save(anyLong(), any(), anyList());
  }

  @Test
  void commitPersistsAndDeletesDraftForOwner() {
    when(draftStore.get("snap_abc"))
        .thenReturn(Optional.of(new Draft(42L, Map.of("k", "v"), List.of("current_content"))));
    LearningContextSnapshot saved = mock(LearningContextSnapshot.class);
    when(saved.getId()).thenReturn(99L);
    when(snapshots.save(any())).thenReturn(saved);

    CommitResponse res =
        service.commit(42L, "snap_abc", new CommitRequest("question", 5L, "answerers_only"));

    assertEquals(99L, res.snapshotId());
    assertEquals("committed", res.status());
    assertTrue(res.immutable());
    verify(snapshots).save(any());
    verify(draftStore).delete("snap_abc");
  }

  @Test
  void commitRejectsNonOwner() {
    when(draftStore.get("snap_abc"))
        .thenReturn(Optional.of(new Draft(42L, Map.of("k", "v"), List.of())));

    assertThrows(ForbiddenException.class,
        () -> service.commit(99L, "snap_abc", new CommitRequest("question", 5L, "public")));
    verify(snapshots, never()).save(any());
    verify(draftStore, never()).delete(any());
  }

  @Test
  void commitMissingDraftThrowsNotFound() {
    when(draftStore.get("nope")).thenReturn(Optional.empty());
    assertThrows(NotFoundException.class,
        () -> service.commit(1L, "nope", new CommitRequest("question", 5L, "public")));
  }

  @Test
  void getSnapshotPrivateOwnerOk() {
    LearningContextSnapshot e =
        mockEntity(5L, 42L, "private", "{\"current_content\":{\"title\":\"t\"}}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));

    SnapshotView view = service.getSnapshot(42L, 5L);

    assertEquals(5L, view.id());
    assertEquals("answerer", view.renderedFor());
    assertTrue(view.content().containsKey("current_content"));
  }

  @Test
  void getSnapshotPrivateNonOwnerForbidden() {
    LearningContextSnapshot e = mockEntity(5L, 42L, "private", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertThrows(ForbiddenException.class, () -> service.getSnapshot(99L, 5L));
  }

  @Test
  void getSnapshotAnswerersOnlyVisibleToAny() {
    LearningContextSnapshot e = mockEntity(5L, 42L, "answerers_only", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertEquals(5L, service.getSnapshot(99L, 5L).id());
  }

  @Test
  void getSnapshotPublicVisibleToAny() {
    LearningContextSnapshot e = mockEntity(5L, 42L, "public", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertEquals(5L, service.getSnapshot(99L, 5L).id());
  }

  @Test
  void getSnapshotMissingThrowsNotFound() {
    when(snapshots.findById(123L)).thenReturn(Optional.empty());
    assertThrows(NotFoundException.class, () -> service.getSnapshot(1L, 123L));
  }

  @Test
  void getPreferencesReturnsDefaultsWhenAbsent() {
    when(preferences.findById(1L)).thenReturn(Optional.empty());
    PreferencesView v = service.getPreferences(1L);
    assertTrue(v.collectCurrentContent());
    assertFalse(v.collectRecentErrors());
    assertEquals("answerers_only", v.defaultVisibility());
  }

  @Test
  void putPreferencesUpserts() {
    when(preferences.findById(1L)).thenReturn(Optional.empty());
    when(preferences.save(any())).thenAnswer(inv -> inv.getArgument(0));

    PreferencesView v = service.putPreferences(1L,
        new PreferencesView(false, true, false, true, false, true, "private"));

    assertFalse(v.collectCurrentContent());
    assertTrue(v.collectRecentErrors());
    assertEquals("private", v.defaultVisibility());
    verify(preferences).save(any());
  }
}
