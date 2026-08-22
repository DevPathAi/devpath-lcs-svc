package ai.devpath.lcs.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real Redis contract for the expiring, policy-bound LCS draft. */
class RedisDraftStoreTest {

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;
  private static RedisDraftStore store;

  @BeforeAll
  static void connectToRealRedis() {
    String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
    connectionFactory = new LettuceConnectionFactory(host, port);
    connectionFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(connectionFactory);
    redis.afterPropertiesSet();
    store = new RedisDraftStore(redis, JsonMapper.builder().build());
    try (var connection = redis.getConnectionFactory().getConnection()) {
      assertEquals("PONG", connection.ping(),
          "real Redis must be reachable for the TTL contract test");
    }
  }

  @AfterAll
  static void closeRedis() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void storesEveryPolicyFieldWithAnExactTenMinuteTtl() {
    Draft expected = new Draft(42L, "mentor_prompt", 10L,
        List.of("current_content", "current_code"), "private",
        Map.of("current_content", Map.of("contentId", 10L), "current_code", "print(1)"),
        List.of("current_content", "current_code"));
    String id = store.save(expected);
    String key = RedisDraftStore.key(id);
    try {
      long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
      assertTrue(ttlSeconds >= 598 && ttlSeconds <= 600,
          "new drafts must have a ten-minute TTL, actual seconds=" + ttlSeconds);
      Draft actual = store.get(id).orElseThrow();
      assertEquals(expected.userId(), actual.userId());
      assertEquals(expected.purpose(), actual.purpose());
      assertEquals(expected.contentId(), actual.contentId());
      assertEquals(expected.requestedFields(), actual.requestedFields());
      assertEquals(expected.forcedVisibility(), actual.forcedVisibility());
      assertEquals(expected.fieldsIncluded(), actual.fieldsIncluded());
      assertEquals("print(1)", actual.content().get("current_code"));
      @SuppressWarnings("unchecked")
      Map<String, Object> currentContent =
          (Map<String, Object>) actual.content().get("current_content");
      assertEquals(10L, ((Number) currentContent.get("contentId")).longValue());
    } finally {
      store.delete(id);
    }
  }

  @Test
  void expiredDraftBecomesUnavailable() throws Exception {
    String id = store.save(new Draft(42L, "mentor_prompt", null,
        List.of("current_content"), "private", Map.of(), List.of()));
    String key = RedisDraftStore.key(id);
    redis.expire(key, Duration.ofMillis(25));

    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (store.get(id).isPresent() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(store.get(id).isPresent(), "expired Redis drafts must not be commit-authoritative");
  }
}
