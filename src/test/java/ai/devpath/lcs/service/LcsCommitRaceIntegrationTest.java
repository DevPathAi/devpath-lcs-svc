package ai.devpath.lcs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.CommitResponse;
import ai.devpath.lcs.domain.LearningContextSnapshotRepository;
import ai.devpath.lcs.draft.Draft;
import ai.devpath.lcs.draft.DraftStore;
import ai.devpath.lcs.draft.RedisDraftStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** Forces DB precheck -> winner commit/delete -> loser Redis miss -> DB replay. */
@SpringBootTest
@ActiveProfiles("test")
class LcsCommitRaceIntegrationTest {

  @Autowired LcsService service;
  @MockitoSpyBean DraftStore draftStore;
  @Autowired LearningContextSnapshotRepository snapshots;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;

  @Test
  void loserReplaysWinnerAfterRedisMissWithoutBusyLoop() throws Exception {
    long userId = 8_801_001L;
    Draft draft = new Draft(userId, "mentor_prompt", null, List.of("current_code"),
        "private", Map.of("current_code", "print('race')"), List.of("current_code"));
    String draftId = draftStore.save(draft);
    RedisDraftStore realStore = new RedisDraftStore(redis, JsonMapper.builder().build());
    CountDownLatch loserAtRedis = new CountDownLatch(1);
    CountDownLatch winnerFinished = new CountDownLatch(1);

    doAnswer(invocation -> {
      if (Thread.currentThread().getName().equals("lcs-race-loser")) {
        loserAtRedis.countDown();
        assertTrue(winnerFinished.await(10, TimeUnit.SECONDS));
      }
      return realStore.get(draftId);
    }).when(draftStore).get(draftId);

    var executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "lcs-race-loser");
      thread.setDaemon(true);
      return thread;
    });
    try {
      var loser = executor.submit(() -> service.commit(
          userId, draftId, new CommitRequest(null, null, null)));
      assertTrue(loserAtRedis.await(10, TimeUnit.SECONDS),
          "loser must finish its empty DB precheck before the winner commits");

      CommitResponse winner = service.commit(
          userId, draftId, new CommitRequest(null, null, null));
      assertTrue(realStore.get(draftId).isEmpty(),
          "winner must delete Redis only after its REQUIRES_NEW DB commit");
      winnerFinished.countDown();

      CommitResponse replay = loser.get(10, TimeUnit.SECONDS);
      assertEquals(winner.snapshotId(), replay.snapshotId());
      assertEquals(1, jdbc.queryForObject(
          "select count(*) from learning_context_snapshots where source_draft_id = ?",
          Integer.class, draftId));
    } finally {
      winnerFinished.countDown();
      executor.shutdownNow();
      reset(draftStore);
      redis.delete("lcs:draft:" + draftId);
      jdbc.update("delete from learning_context_snapshots where source_draft_id = ?", draftId);
      assertTrue(executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
    }
  }
}
