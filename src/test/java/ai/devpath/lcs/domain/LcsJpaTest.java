package ai.devpath.lcs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 엔티티 ↔ shared 중앙 마이그레이션 검증(validate). postgres 필요 → CI(postgres+redis)에서 실행.
 * 로컬(무 postgres)에서는 컨텍스트 로딩 실패로 skip; 컴파일만 보장.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.test.database.replace=none")
@ActiveProfiles("test")
class LcsJpaTest {

  @Autowired LearningContextSnapshotRepository snapshots;
  @Autowired UserContextPreferenceRepository preferences;

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
}
