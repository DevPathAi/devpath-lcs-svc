package ai.devpath.lcs.draft;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Redis 기반 draft 저장소. key = lcs:draft:{token}, TTL 10분, 값 = Draft(JSON). */
@Component
public class RedisDraftStore implements DraftStore {

  private static final String KEY_PREFIX = "lcs:draft:";
  private static final Duration TTL = Duration.ofMinutes(10);

  private final StringRedisTemplate redis;
  private final JsonMapper jsonMapper;

  public RedisDraftStore(StringRedisTemplate redis, JsonMapper jsonMapper) {
    this.redis = redis;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String save(long userId, Map<String, Object> content, List<String> fieldsIncluded) {
    String token = "snap_" + UUID.randomUUID();
    Draft draft = new Draft(userId, content, fieldsIncluded);
    redis.opsForValue().set(KEY_PREFIX + token, jsonMapper.writeValueAsString(draft), TTL);
    return token;
  }

  @Override
  public Optional<Draft> get(String draftId) {
    String json = redis.opsForValue().get(KEY_PREFIX + draftId);
    if (json == null) {
      return Optional.empty();
    }
    return Optional.of(jsonMapper.readValue(json, Draft.class));
  }

  @Override
  public void delete(String draftId) {
    redis.delete(KEY_PREFIX + draftId);
  }
}
