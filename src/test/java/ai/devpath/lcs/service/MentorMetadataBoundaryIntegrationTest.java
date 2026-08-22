package ai.devpath.lcs.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import ai.devpath.lcs.domain.LearningContextSnapshotRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Redis/PostgreSQL consumer contract with a real HTTP metadata boundary. */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MentorMetadataBoundaryIntegrationTest {

  private static final String SECRET_CODE = "SECRET_CODE";
  private static final String SECRET_STDOUT = "SECRET_STDOUT";
  private static final String SECRET_STDERR = "SECRET_STDERR";
  private static final MockWebServer SANDBOX = startServer();

  @Autowired LcsService service;
  @Autowired LearningContextSnapshotRepository snapshots;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void sandboxProperties(DynamicPropertyRegistry registry) {
    registry.add("devpath.sandbox.base-url", () -> SANDBOX.url("/").toString());
  }

  @AfterAll
  static void stopServer() throws IOException {
    SANDBOX.shutdown();
  }

  @Test
  void exactMetadataSurvivesRedisCommitAndConsumeWithoutRawFields(CapturedOutput output)
      throws Exception {
    SANDBOX.enqueue(json("[{\"language\":\"JAVA\",\"status\":\"COMPLETED\"}]"));
    DraftResponse draft = service.draft(8_802_001L,
        new DraftRequest("mentor_prompt", null, List.of("recent_activity"), Map.of()));
    try {
      assertThat(draft.content()).isEqualTo(Map.of(
          "recent_activity", List.of(Map.of("language", "JAVA", "status", "COMPLETED"))));
      String redisJson = redis.opsForValue().get("lcs:draft:" + draft.draftId());
      assertThat(redisJson).doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);

      long snapshotId = service.commit(8_802_001L, draft.draftId(),
          new CommitRequest(null, null, null)).snapshotId();
      var stored = snapshots.findById(snapshotId).orElseThrow();
      assertThat(stored.getContentSnapshot())
          .doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);
      assertThat(service.consumeMentorSnapshot(8_802_001L, snapshotId).content())
          .isEqualTo(draft.content());
      assertThat(output.getAll()).doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);
      assertThat(SANDBOX.takeRequest().getPath())
          .isEqualTo("/internal/sandbox/sessions/recent/metadata?userId=8802001&limit=5");
    } finally {
      cleanup(draft.draftId());
    }
  }

  @Test
  void legacyRawShapeFailsClosedAndSecretsNeverReachDraftDatabaseConsumeOrLogs(
      CapturedOutput output) throws Exception {
    SANDBOX.enqueue(json("[{\"language\":\"JAVA\",\"status\":\"COMPLETED\","
        + "\"submittedCode\":\"" + SECRET_CODE + "\","
        + "\"stdout\":\"" + SECRET_STDOUT + "\","
        + "\"stderr\":\"" + SECRET_STDERR + "\"}]"));
    DraftResponse draft = service.draft(8_802_002L,
        new DraftRequest("mentor_prompt", null, List.of("recent_activity"), Map.of()));
    try {
      assertThat(draft.fieldsAvailable()).isEmpty();
      assertThat(draft.fieldsUnavailable())
          .anySatisfy(item -> {
            assertThat(item.field()).isEqualTo("recent_activity");
            assertThat(item.reason()).isEqualTo("source_unavailable");
          });
      String redisJson = redis.opsForValue().get("lcs:draft:" + draft.draftId());
      assertThat(redisJson).doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);

      long snapshotId = service.commit(8_802_002L, draft.draftId(),
          new CommitRequest(null, null, null)).snapshotId();
      var stored = snapshots.findById(snapshotId).orElseThrow();
      assertThat(stored.getContentSnapshot()).isEqualTo("{}");
      assertThat(stored.getContentSnapshot())
          .doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);
      assertThat(service.consumeMentorSnapshot(8_802_002L, snapshotId).content()).isEmpty();
      assertThat(output.getAll()).doesNotContain(SECRET_CODE, SECRET_STDOUT, SECRET_STDERR);
      assertThat(SANDBOX.takeRequest().getPath())
          .isEqualTo("/internal/sandbox/sessions/recent/metadata?userId=8802002&limit=5");
    } finally {
      cleanup(draft.draftId());
    }
  }

  private void cleanup(String draftId) {
    redis.delete("lcs:draft:" + draftId);
    jdbc.update("delete from learning_context_snapshots where source_draft_id = ?", draftId);
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }

  private static MockWebServer startServer() {
    MockWebServer server = new MockWebServer();
    try {
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
