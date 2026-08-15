package ai.devpath.lcs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.CommitResponse;
import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import ai.devpath.lcs.api.MentorSnapshotView;
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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class LcsServiceTest {

  private static final String QUESTION_DRAFT_ID =
      "snap_11111111-1111-4111-8111-111111111111";

  private final SnapshotAssembler assembler = mock(SnapshotAssembler.class);
  private final DraftStore draftStore = mock(DraftStore.class);
  private final LearningContextSnapshotRepository snapshots =
      mock(LearningContextSnapshotRepository.class);
  private final MentorSnapshotCommitter mentorCommitter = mock(MentorSnapshotCommitter.class);
  private final UserContextPreferenceRepository preferences =
      mock(UserContextPreferenceRepository.class);
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private final LcsService service =
      new LcsService(assembler, draftStore, snapshots, mentorCommitter, preferences, jsonMapper);

  private LearningContextSnapshot mockEntity(long id, long userId, String purpose,
      String visibility, String json) {
    LearningContextSnapshot e = mock(LearningContextSnapshot.class);
    when(e.getId()).thenReturn(id);
    when(e.getUserId()).thenReturn(userId);
    when(e.getPurpose()).thenReturn(purpose);
    when(e.getVisibility()).thenReturn(visibility);
    when(e.getContentSnapshot()).thenReturn(json);
    when(e.getFieldsIncluded()).thenReturn("[]");
    when(e.getCreatedAt()).thenReturn(Instant.now());
    return e;
  }

  @Test
  void draftAssemblesAndStores() {
    when(preferences.findById(1L)).thenReturn(Optional.empty());
    when(assembler.assemble(anyString(), anyLong(), any(), anyList(), anyMap(), any()))
        .thenReturn(new AssemblyResult(Map.of("current_content", Map.of("title", "t")),
            List.of("current_content"), List.of()));
    when(draftStore.save(any(Draft.class))).thenReturn("snap_abc");

    DraftResponse res = service.draft(1L, new DraftRequest("question_attachment", 10L, null));

    assertEquals("snap_abc", res.draftId());
    assertTrue(res.fieldsAvailable().contains("current_content"));
    assertTrue(res.expiresAt().isAfter(Instant.now()));
    ArgumentCaptor<Draft> captured = ArgumentCaptor.forClass(Draft.class);
    verify(draftStore).save(captured.capture());
    assertEquals("question_attachment", captured.getValue().purpose());
    assertEquals(10L, captured.getValue().contentId());
    assertEquals(null, captured.getValue().forcedVisibility());
    assertEquals(List.of("current_content", "recent_activity", "active_tags",
        "tag_reputation", "current_path", "recent_errors"),
        captured.getValue().requestedFields());
  }

  @Test
  void mentorDraftPersistsPurposeSelectionContentBindingAndForcedPrivatePolicy() {
    when(preferences.findById(42L)).thenReturn(Optional.empty());
    when(assembler.assemble(anyString(), anyLong(), any(), anyList(), anyMap(), any()))
        .thenReturn(new AssemblyResult(Map.of("current_content", Map.of("contentId", 10L)),
            List.of("current_content"), List.of()));
    when(draftStore.save(any(Draft.class))).thenReturn("snap_abc");

    service.draft(42L, new DraftRequest("mentor_prompt", 10L, List.of(), Map.of()));

    ArgumentCaptor<Draft> captured = ArgumentCaptor.forClass(Draft.class);
    verify(draftStore).save(captured.capture());
    Draft draft = captured.getValue();
    assertEquals(42L, draft.userId());
    assertEquals("mentor_prompt", draft.purpose());
    assertEquals(10L, draft.contentId());
    assertEquals(List.of("current_content"), draft.requestedFields());
    assertEquals("private", draft.forcedVisibility());
    assertEquals(List.of("current_content"), draft.fieldsIncluded());
  }

  @Test
  void communityDraftRejectsMentorOnlySelectionAndRequestContextBeforeAssembly() {
    assertThrows(IllegalArgumentException.class, () -> service.draft(42L,
        new DraftRequest("question_attachment", 10L, List.of("current_code"),
            Map.of("current_code", "must not become public"))));
    assertThrows(IllegalArgumentException.class, () -> service.draft(42L,
        new DraftRequest("question_attachment", 10L, List.of("recent_errors"),
            Map.of("recent_errors", List.of("must not become answerers_only")))));
    verify(assembler, never()).assemble(
        anyString(), anyLong(), any(), anyList(), anyMap(), any());
    verify(draftStore, never()).save(any(Draft.class));
  }

  @Test
  void commitPersistsAndDeletesDraftForOwner() {
    when(snapshots.findBySourceDraftId(QUESTION_DRAFT_ID)).thenReturn(Optional.empty());
    when(draftStore.get(QUESTION_DRAFT_ID))
        .thenReturn(Optional.of(Draft.legacyQuestion(
            42L, Map.of("k", "v"), List.of("current_content"))));
    LearningContextSnapshot saved = mock(LearningContextSnapshot.class);
    when(saved.getId()).thenReturn(99L);
    when(snapshots.save(any())).thenReturn(saved);

    CommitResponse res =
        service.commit(42L, QUESTION_DRAFT_ID,
            new CommitRequest("question", 5L, "answerers_only"));

    assertEquals(99L, res.snapshotId());
    assertEquals("committed", res.status());
    assertTrue(res.immutable());
    verify(snapshots).save(any());
    verify(draftStore).delete(QUESTION_DRAFT_ID);
    ArgumentCaptor<LearningContextSnapshot> snapshot =
        ArgumentCaptor.forClass(LearningContextSnapshot.class);
    verify(snapshots).save(snapshot.capture());
    assertEquals("question_attachment", snapshot.getValue().getPurpose());
    assertEquals("question", snapshot.getValue().getAttachedToType());
    assertEquals(5L, snapshot.getValue().getAttachedToId());
    assertEquals("answerers_only", snapshot.getValue().getVisibility());
  }

  @Test
  void commitRejectsNonOwner() {
    when(snapshots.findBySourceDraftId(QUESTION_DRAFT_ID)).thenReturn(Optional.empty());
    when(draftStore.get(QUESTION_DRAFT_ID))
        .thenReturn(Optional.of(Draft.legacyQuestion(42L, Map.of("k", "v"), List.of())));

    assertThrows(ForbiddenException.class,
        () -> service.commit(99L, QUESTION_DRAFT_ID,
            new CommitRequest("question", 5L, "public")));
    verify(snapshots, never()).save(any());
    verify(draftStore, never()).delete(any());
  }

  @Test
  void mentorCommitRejectsEveryTargetOrVisibilityOverride() {
    String draftId = "snap_aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    Draft draft = new Draft(42L, "mentor_prompt", 10L, List.of("current_content"),
        "private", Map.of("current_content", Map.of("contentId", 10L)),
        List.of("current_content"));
    when(snapshots.findBySourceDraftId(draftId)).thenReturn(Optional.empty());
    when(draftStore.get(draftId)).thenReturn(Optional.of(draft));

    for (CommitRequest override : List.of(
        new CommitRequest("question", null, null),
        new CommitRequest(null, 7L, null),
        new CommitRequest(null, null, "private"),
        new CommitRequest(null, null, "public"))) {
      assertThrows(IllegalArgumentException.class,
          () -> service.commit(42L, draftId, override));
    }
    verify(mentorCommitter, never()).commit(anyLong(), anyString(), anyString(), anyString());
    verify(draftStore, never()).delete(anyString());
  }

  @Test
  void mentorCommitUsesBoundPrivatePolicyAndCleanupFailureDoesNotLoseCommit() {
    String draftId = "snap_bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    Draft draft = new Draft(42L, "mentor_prompt", 10L, List.of("current_code"),
        "private", Map.of("current_code", "print(1)"), List.of("current_code"));
    when(snapshots.findBySourceDraftId(draftId)).thenReturn(Optional.empty());
    when(draftStore.get(draftId)).thenReturn(Optional.of(draft));
    when(mentorCommitter.commit(42L, draftId, "{\"current_code\":\"print(1)\"}",
        "[\"current_code\"]"))
        .thenReturn(new MentorCommitResult(91L, 42L, "mentor_prompt", "private"));
    org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
        .when(draftStore).delete(draftId);

    CommitResponse response = service.commit(42L, draftId, new CommitRequest(null, null, null));

    assertEquals(91L, response.snapshotId());
    assertTrue(response.immutable());
    verify(draftStore).delete(draftId);
  }

  @Test
  void mentorCommitRejectsTamperedDraftSelectionAndContentBinding() {
    String unknownSelectionId = "snap_bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb";
    Draft unknownSelection = new Draft(42L, "mentor_prompt", null, List.of("evil"),
        "private", Map.of(), List.of());
    when(snapshots.findBySourceDraftId(unknownSelectionId)).thenReturn(Optional.empty());
    when(draftStore.get(unknownSelectionId)).thenReturn(Optional.of(unknownSelection));

    String mismatchedContentId = "snap_bbbbbbbb-bbbb-4bbb-abbb-bbbbbbbbbbbb";
    Draft mismatchedContent = new Draft(42L, "mentor_prompt", 10L,
        List.of("current_content"), "private",
        Map.of("current_content", Map.of(
            "contentId", 11L, "title", "title", "track", "java")),
        List.of("current_content"));
    when(snapshots.findBySourceDraftId(mismatchedContentId)).thenReturn(Optional.empty());
    when(draftStore.get(mismatchedContentId)).thenReturn(Optional.of(mismatchedContent));

    for (String draftId : List.of(unknownSelectionId, mismatchedContentId)) {
      ForbiddenException denied = assertThrows(ForbiddenException.class,
          () -> service.commit(42L, draftId, new CommitRequest(null, null, null)));
      assertEquals("snapshot unavailable", denied.getMessage());
    }
    verify(mentorCommitter, never()).commit(anyLong(), anyString(), anyString(), anyString());
    verify(draftStore, never()).delete(anyString());
  }

  @Test
  void committedMentorReplayWorksAfterRedisExpiryAndDifferentOwnerGetsGenericDenial() {
    String draftId = "snap_cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    LearningContextSnapshot existing = mockEntity(
        92L, 42L, "mentor_prompt", "private", "{\"current_content\":{}}");
    when(existing.getSourceDraftId()).thenReturn(draftId);
    when(snapshots.findBySourceDraftId(draftId)).thenReturn(Optional.of(existing));

    CommitResponse replay =
        service.commit(42L, draftId, new CommitRequest(null, null, null));
    assertEquals(92L, replay.snapshotId());
    verify(draftStore, never()).get(anyString());

    ForbiddenException denied = assertThrows(ForbiddenException.class,
        () -> service.commit(99L, draftId, new CommitRequest(null, null, null)));
    assertEquals("mentor snapshot unavailable", denied.getMessage());
    verify(draftStore, never()).get(anyString());
  }

  @Test
  void invalidDraftIdentityFailsBeforeRedisOrDatabaseWrite() {
    assertThrows(IllegalArgumentException.class,
        () -> service.commit(42L, "SNAP_AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
            new CommitRequest(null, null, null)));
    verify(snapshots, never()).findBySourceDraftId(anyString());
    verify(draftStore, never()).get(anyString());
    verify(mentorCommitter, never()).commit(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void commitMissingDraftThrowsNotFound() {
    String missing = "snap_22222222-2222-4222-8222-222222222222";
    when(snapshots.findBySourceDraftId(missing)).thenReturn(Optional.empty());
    when(draftStore.get(missing)).thenReturn(Optional.empty());
    assertThrows(NotFoundException.class,
        () -> service.commit(1L, missing, new CommitRequest("question", 5L, "public")));
  }

  @Test
  void getSnapshotPrivateOwnerOk() {
    LearningContextSnapshot e =
        mockEntity(5L, 42L, "question_attachment", "private",
            "{\"current_content\":{\"title\":\"t\"}}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));

    SnapshotView view = service.getSnapshot(42L, 5L);

    assertEquals(5L, view.id());
    assertEquals("answerer", view.renderedFor());
    assertTrue(view.content().containsKey("current_content"));
  }

  @Test
  void getSnapshotPrivateNonOwnerForbidden() {
    LearningContextSnapshot e =
        mockEntity(5L, 42L, "question_attachment", "private", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertThrows(ForbiddenException.class, () -> service.getSnapshot(99L, 5L));
  }

  @Test
  void getSnapshotAnswerersOnlyVisibleToAny() {
    LearningContextSnapshot e =
        mockEntity(5L, 42L, "question_attachment", "answerers_only", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertEquals(5L, service.getSnapshot(99L, 5L).id());
  }

  @Test
  void getSnapshotPublicVisibleToAny() {
    LearningContextSnapshot e =
        mockEntity(5L, 42L, "question_attachment", "public", "{}");
    when(snapshots.findById(5L)).thenReturn(Optional.of(e));
    assertEquals(5L, service.getSnapshot(99L, 5L).id());
  }

  @Test
  void getSnapshotMissingThrowsNotFound() {
    when(snapshots.findById(123L)).thenReturn(Optional.empty());
    assertThrows(NotFoundException.class, () -> service.getSnapshot(1L, 123L));
  }

  @Test
  void byQuestionReturnsCommittedSnapshotForAnswerer() {
    LearningContextSnapshot e =
        mockEntity(7L, 42L, "question_attachment", "answerers_only",
            "{\"current_content\":{\"title\":\"t\"}}");
    when(snapshots.findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc("question", 5L))
        .thenReturn(Optional.of(e));

    SnapshotView view = service.getSnapshotByQuestion(99L, 5L);

    assertEquals(7L, view.id());
    assertEquals("answerer", view.renderedFor());
    assertTrue(view.content().containsKey("current_content"));
  }

  @Test
  void byQuestionMissingThrowsNotFound() {
    when(snapshots.findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc("question", 123L))
        .thenReturn(Optional.empty());
    assertThrows(NotFoundException.class, () -> service.getSnapshotByQuestion(1L, 123L));
  }

  @Test
  void byQuestionPrivateNonOwnerForbidden() {
    LearningContextSnapshot e =
        mockEntity(7L, 42L, "question_attachment", "private", "{}");
    when(snapshots.findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc("question", 5L))
        .thenReturn(Optional.of(e));
    assertThrows(ForbiddenException.class, () -> service.getSnapshotByQuestion(99L, 5L));
  }

  @Test
  void mentorConsumeReturnsStrictExactEnvelopeForOwner() {
    LearningContextSnapshot e = mockEntity(101L, 42L, "mentor_prompt", "private",
        "{\"current_code\":\"print(1)\",\"recent_output\":{\"stdout\":\"ok\","
            + "\"stderr\":\"\",\"truncated\":false}}");
    when(e.getFieldsIncluded()).thenReturn("[\"current_code\",\"recent_output\"]");
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        101L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(e));

    MentorSnapshotView view = service.consumeMentorSnapshot(42L, 101L);

    assertEquals(101L, view.snapshotId());
    assertEquals("mentor_prompt", view.purpose());
    assertEquals("private", view.visibility());
    assertEquals(List.of("current_code", "recent_output"), view.fieldsIncluded());
    assertEquals(Map.of(
        "current_code", "print(1)",
        "recent_output", Map.of("stdout", "ok", "stderr", "", "truncated", false)),
        view.content(), "consume content must be deeply equal to the committed JSON");
    assertEquals(new java.util.HashSet<>(view.fieldsIncluded()), view.content().keySet());
  }

  @Test
  void mentorConsumeFailsClosedForOwnerPurposeVisibilityAndFieldMismatch() {
    LearningContextSnapshot wrongOwner =
        mockEntity(102L, 42L, "mentor_prompt", "private", "{}");
    LearningContextSnapshot wrongPurpose =
        mockEntity(103L, 42L, "question_attachment", "private", "{}");
    LearningContextSnapshot wrongVisibility =
        mockEntity(104L, 42L, "mentor_prompt", "public", "{}");
    LearningContextSnapshot mismatched =
        mockEntity(105L, 42L, "mentor_prompt", "private", "{\"current_code\":\"x\"}");
    when(mismatched.getFieldsIncluded()).thenReturn("[]");
    LearningContextSnapshot unknown =
        mockEntity(106L, 42L, "mentor_prompt", "private", "{\"evil\":\"raw\"}");
    when(unknown.getFieldsIncluded()).thenReturn("[\"evil\"]");
    LearningContextSnapshot malformed =
        mockEntity(107L, 42L, "mentor_prompt", "private", "{\"current_code\":7}");
    when(malformed.getFieldsIncluded()).thenReturn("[\"current_code\"]");
    LearningContextSnapshot rawRun = mockEntity(108L, 42L, "mentor_prompt", "private",
        "{\"recent_activity\":[{\"language\":\"java\",\"status\":\"SUCCESS\","
            + "\"submittedCode\":\"secret\"}]}");
    when(rawRun.getFieldsIncluded()).thenReturn("[\"recent_activity\"]");
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        105L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(mismatched));
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        106L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(unknown));
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        107L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(malformed));
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        108L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(rawRun));

    for (long id : List.of(102L, 103L, 104L, 105L, 106L, 107L, 108L, 999L)) {
      NotFoundException denied = assertThrows(NotFoundException.class,
          () -> service.consumeMentorSnapshot(id == 102L ? 99L : 42L, id));
      assertEquals("mentor snapshot unavailable", denied.getMessage());
    }
  }

  @Test
  void mentorConsumeAllowsAnEmptyPartialSnapshot() {
    LearningContextSnapshot empty =
        mockEntity(109L, 42L, "mentor_prompt", "private", "{}");
    when(empty.getFieldsIncluded()).thenReturn("[]");
    when(snapshots.findByIdAndUserIdAndPurposeAndVisibility(
        109L, 42L, "mentor_prompt", "private")).thenReturn(Optional.of(empty));

    MentorSnapshotView view = service.consumeMentorSnapshot(42L, 109L);

    assertEquals(List.of(), view.fieldsIncluded());
    assertEquals(Map.of(), view.content());
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
