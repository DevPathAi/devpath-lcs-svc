package ai.devpath.lcs.release;

import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Staging-only proof that a private Mentor preview was assembled for an exact browser run. */
@Component
public class ReleasePreviewRegistry {
  private static final Pattern CANDIDATE = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern RUN_KEY = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
  private static final int MAX_RUNS = 64;

  private final boolean enabled;
  private final Map<Key, Snapshot> runs = new ConcurrentHashMap<>();

  public ReleasePreviewRegistry(@Value("${devpath.release.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public void record(
      String candidate,
      String runKey,
      long userId,
      DraftRequest request,
      DraftResponse response) {
    if (!enabled || candidate == null || runKey == null) return;
    if (!valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) return;
    Key key = new Key(candidate, runKey);
    if (request == null || !"mentor_prompt".equals(request.purpose())) return;
    if (userId <= 0 || response == null || response.draftId() == null
        || !response.draftId().startsWith("snap_") || response.fieldsAvailable() == null) {
      throw new IllegalArgumentException("release Mentor preview is invalid");
    }
    if (!runs.containsKey(key) && runs.size() >= MAX_RUNS) {
      throw new IllegalStateException("LCS release run capacity is exhausted");
    }
    runs.compute(key, (ignored, current) -> {
      if (current != null && current.userId() != userId) {
        throw new IllegalArgumentException("LCS release owner binding does not match");
      }
      return new Snapshot(userId, true, response.fieldsAvailable().size());
    });
  }

  public boolean checkpoint(String candidate, String runKey, String checkpoint) {
    if (!enabled || !"private-mentor-prompt-preview".equals(checkpoint)
        || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) return false;
    Snapshot snapshot = runs.get(new Key(candidate, runKey));
    return snapshot != null && snapshot.userId() > 0 && snapshot.privateMentorPreview();
  }

  public Snapshot snapshot(String candidate, String runKey) {
    Snapshot snapshot = runs.get(requireKey(candidate, runKey));
    if (snapshot == null) throw new IllegalStateException("LCS release run is unavailable");
    return snapshot;
  }

  private static Key requireKey(String candidate, String runKey) {
    if (!valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) {
      throw new IllegalArgumentException("LCS release binding is invalid");
    }
    return new Key(candidate, runKey);
  }

  private static boolean valid(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches();
  }

  private record Key(String candidate, String runKey) {}

  public record Snapshot(long userId, boolean privateMentorPreview, int fieldsAvailableCount) {}
}
