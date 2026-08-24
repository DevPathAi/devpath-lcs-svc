package ai.devpath.lcs.release;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleasePreviewRegistryTest {
  private static final String CANDIDATE = "a".repeat(64);
  private static final String RUN = "R".repeat(43);

  @Test
  void recordsOnlyAnOwnerBoundMentorPreviewWithoutRawContext() {
    ReleasePreviewRegistry registry = new ReleasePreviewRegistry(true);
    DraftRequest request = new DraftRequest(
        "mentor_prompt", 7L, List.of("current_content"),
        Map.of("current_content", Map.of("code", "secret")));
    DraftResponse response = new DraftResponse(
        "snap_12345678-1234-4123-8123-123456789abc",
        Instant.now().plusSeconds(600),
        Map.of("current_content", Map.of("code", "secret")),
        List.of("current_content"),
        List.of());

    registry.record(CANDIDATE, RUN, 42L, request, response);

    assertThat(registry.checkpoint(
        CANDIDATE, RUN, "private-mentor-prompt-preview")).isTrue();
    assertThat(registry.snapshot(CANDIDATE, RUN).toString())
        .doesNotContain("current_content", "code", "secret", "snap_");
  }

  @Test
  void disabledOrNonMentorTrafficCannotCreateReleaseEvidence() {
    ReleasePreviewRegistry disabled = new ReleasePreviewRegistry(false);
    DraftRequest mentor = new DraftRequest("mentor_prompt", null, List.of(), Map.of());
    DraftResponse response = new DraftResponse(
        "snap_12345678-1234-4123-8123-123456789abc",
        Instant.now(), Map.of(), List.of(), List.of());
    disabled.record(CANDIDATE, RUN, 42L, mentor, response);
    assertThat(disabled.checkpoint(
        CANDIDATE, RUN, "private-mentor-prompt-preview")).isFalse();

    ReleasePreviewRegistry enabled = new ReleasePreviewRegistry(true);
    enabled.record(CANDIDATE, RUN, 42L,
        new DraftRequest("question_attachment", null, List.of(), Map.of()), response);
    assertThat(enabled.checkpoint(
        CANDIDATE, RUN, "private-mentor-prompt-preview")).isFalse();
    enabled.record("bad", RUN, 42L, mentor, response);
    assertThat(enabled.checkpoint(
        "bad", RUN, "private-mentor-prompt-preview")).isFalse();
  }
}
