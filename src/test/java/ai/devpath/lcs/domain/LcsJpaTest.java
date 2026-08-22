package ai.devpath.lcs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.devpath.lcs.service.MentorCommitResult;
import ai.devpath.lcs.service.MentorSnapshotCommitter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 엔티티 ↔ shared 중앙 마이그레이션 검증(validate). postgres 필요 → CI(postgres+redis)에서 실행.
 * 로컬(무 postgres)에서는 컨텍스트 로딩 실패로 skip; 컴파일만 보장.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.test.database.replace=none")
@ActiveProfiles("test")
@Import(MentorSnapshotCommitter.class)
class LcsJpaTest {

  @Autowired LearningContextSnapshotRepository snapshots;
  @Autowired UserContextPreferenceRepository preferences;
  @Autowired MentorSnapshotCommitter mentorCommitter;
  @Autowired DataSource dataSource;

  @Test
  void savesAndReadsSnapshot() {
    LearningContextSnapshot s = new LearningContextSnapshot(
        42L, "question_attachment", "question", 5L,
        "{\"current_content\":{\"title\":\"t\"}}", "answerers_only", "[\"current_content\"]");
    LearningContextSnapshot saved = snapshots.save(s);
    assertNotNull(saved.getId());
    assertNotNull(saved.getCreatedAt());
    assertEquals("answerers_only", snapshots.findById(saved.getId()).orElseThrow().getVisibility());
  }

  @Test
  void findsCommittedSnapshotByQuestion() {
    LearningContextSnapshot s = new LearningContextSnapshot(
        42L, "question_attachment", "question", 555L,
        "{\"current_content\":{\"title\":\"t\"}}", "answerers_only", "[\"current_content\"]");
    snapshots.save(s);

    Optional<LearningContextSnapshot> found =
        snapshots.findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc("question", 555L);
    assertTrue(found.isPresent());
    assertEquals(555L, found.orElseThrow().getAttachedToId());

    assertTrue(snapshots
        .findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc("question", 999L)
        .isEmpty());
  }

  @Test
  void savesPreferenceWithDefaults() {
    UserContextPreference p = new UserContextPreference(77L);
    preferences.save(p);
    UserContextPreference found = preferences.findById(77L).orElseThrow();
    assertTrue(found.isCollectCurrentContent());
    assertFalse(found.isCollectRecentErrors());
    assertEquals("answerers_only", found.getDefaultVisibility());
    assertNotNull(found.getCreatedAt());
    assertNotNull(found.getUpdatedAt());
  }

  @Test
  void concurrentMentorCommitReplaysOneDatabaseSnapshot() throws Exception {
    String draftId = "snap_33333333-3333-4333-8333-333333333333";
    deleteByDraftId(draftId);
    int workers = 8;
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(workers);
    var futures = new ArrayList<java.util.concurrent.Future<MentorCommitResult>>();
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          return mentorCommitter.commit(42L, draftId,
              "{\"current_code\":\"print(1)\"}", "[\"current_code\"]");
        }));
      }
      start.countDown();
      HashSet<Long> ids = new HashSet<>();
      for (var future : futures) {
        MentorCommitResult result = future.get();
        assertEquals(42L, result.userId());
        assertEquals("mentor_prompt", result.purpose());
        assertEquals("private", result.visibility());
        ids.add(result.snapshotId());
      }
      assertEquals(1, ids.size(), "all concurrent commits must return the same snapshot ID");
      LearningContextSnapshot stored = snapshots.findBySourceDraftId(draftId).orElseThrow();
      assertEquals(ids.iterator().next(), stored.getId());
      assertEquals("{\"current_code\": \"print(1)\"}", stored.getContentSnapshot());
      assertEquals("[\"current_code\"]", stored.getFieldsIncluded());
    } finally {
      executor.shutdownNow();
      deleteByDraftId(draftId);
    }
  }

  @Test
  void failedMentorInsertLeavesNoCommittedReplayRow() {
    String invalidDraftId = "SNAP_44444444-4444-4444-8444-444444444444";
    assertThrows(DataIntegrityViolationException.class, () -> mentorCommitter.commit(
        42L, invalidDraftId, "{}", "[]"));
    assertTrue(snapshots.findBySourceDraftId(invalidDraftId).isEmpty());
  }

  private void deleteByDraftId(String draftId) throws Exception {
    try (var c = dataSource.getConnection(); var ps = c.prepareStatement(
        "DELETE FROM learning_context_snapshots WHERE source_draft_id=?")) {
      ps.setString(1, draftId);
      ps.executeUpdate();
    }
  }
}
