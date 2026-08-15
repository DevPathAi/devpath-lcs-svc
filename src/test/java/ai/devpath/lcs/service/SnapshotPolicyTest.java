package ai.devpath.lcs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotPolicyTest {

  @Test
  void mentorEmptySelectionDefaultsOnlyToCurrentContent() {
    assertEquals(List.of("current_content"),
        SnapshotPolicy.normalizeRequestedFields("mentor_prompt", List.of()));
    assertEquals(List.of("current_content"),
        SnapshotPolicy.normalizeRequestedFields("mentor_prompt", null));
  }

  @Test
  void communityEmptySelectionKeepsTheLegacyFieldSet() {
    assertEquals(List.of("current_content", "recent_activity", "active_tags",
            "tag_reputation", "current_path", "recent_errors"),
        SnapshotPolicy.normalizeRequestedFields("question_attachment", List.of()));
  }

  @Test
  void rejectsUnknownPurposeUnknownFieldsDuplicatesAndDeselectedContext() {
    assertThrows(IllegalArgumentException.class,
        () -> SnapshotPolicy.normalizeRequestedFields("unknown", List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> SnapshotPolicy.normalizeRequestedFields("mentor_prompt", List.of("mission")));
    assertThrows(IllegalArgumentException.class,
        () -> SnapshotPolicy.normalizeRequestedFields(
            "mentor_prompt", List.of("current_code", "current_code")));
    assertThrows(IllegalArgumentException.class,
        () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
            List.of("current_content"), Map.of("current_code", "class Main {}")));
  }

  @Test
  void communityCannotSelectOrSupplyMentorOnlySensitiveFields() {
    for (String field : List.of(
        "current_code", "recent_output", "review_summary")) {
      assertThrows(IllegalArgumentException.class,
          () -> SnapshotPolicy.normalizeRequestedFields("question_attachment", List.of(field)));
    }
    assertThrows(IllegalArgumentException.class,
        () -> SnapshotPolicy.validateRequestContext("question_attachment",
            List.of("recent_errors"), Map.of("recent_errors", List.of("must not attach"))));

    assertEquals(List.of("current_content", "recent_activity", "active_tags",
            "tag_reputation", "current_path", "recent_errors"),
        SnapshotPolicy.normalizeRequestedFields("question_attachment", List.of()));
  }

  @Test
  void validatesOneRequestCodeErrorsOutputAndReviewShapes() {
    Map<String, Object> review = Map.of(
        "confidence", 91,
        "strengths", List.of("명확한 이름"),
        "improvements", List.of(Map.of(
            "message", "널 경계를 확인하세요", "line", 12, "severity", "warning")),
        "security", List.of());
    Map<String, Object> output = Map.of(
        "stdout", "ok", "stderr", "", "truncated", false);
    Map<String, Object> valid = Map.of(
        "current_code", "class Main {}",
        "recent_errors", List.of("NullPointerException"),
        "recent_output", output,
        "review_summary", review);

    assertEquals(valid, SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("current_code", "recent_errors", "recent_output", "review_summary"), valid));

    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("current_code"), Map.of("current_code", 7)));
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("recent_errors"), Map.of("recent_errors", List.of(Map.of("message", "x")))));
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("recent_output"), Map.of("recent_output", Map.of("stdout", "x", "secret", "y"))));
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("review_summary"), Map.of("review_summary", Map.of("confidence", 101,
            "strengths", List.of(), "improvements", List.of(), "security", List.of()))));
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("review_summary"), Map.of("review_summary", Map.of("confidence", 50,
            "strengths", List.of(), "improvements", List.of(Map.of(
                "message", "x", "line", 1, "severity", "critical")),
            "security", List.of()))));
  }

  @Test
  void rejectsOversizedSensitiveValuesByUtf8Bytes() {
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("current_code"), Map.of("current_code", "가".repeat(22_000))));
    assertThrows(IllegalArgumentException.class, () -> SnapshotPolicy.validateRequestContext("mentor_prompt",
        List.of("recent_output"), Map.of("recent_output", Map.of(
            "stdout", "x".repeat(65_537), "stderr", "", "truncated", true))));
  }
}
