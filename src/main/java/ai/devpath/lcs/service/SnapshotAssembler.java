package ai.devpath.lcs.service;

import ai.devpath.lcs.api.FieldUnavailable;
import ai.devpath.lcs.client.ContentView;
import ai.devpath.lcs.client.LearningClient;
import ai.devpath.lcs.client.RunView;
import ai.devpath.lcs.client.SandboxClient;
import ai.devpath.lcs.domain.UserContextPreference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 맥락 스냅샷 조립(풀, 멘토 패턴 승계). prefs 로 필드 게이팅 + 소스 풀.
 * 한 소스 실패해도 부분 스냅샷 반환(graceful degradation).
 */
@Component
public class SnapshotAssembler {

  static final String CURRENT_CONTENT = "current_content";
  static final String RECENT_ACTIVITY = "recent_activity";
  static final String ACTIVE_TAGS = "active_tags";
  static final String TAG_REPUTATION = "tag_reputation";
  static final String CURRENT_PATH = "current_path";
  static final String RECENT_ERRORS = "recent_errors";

  static final String REASON_PREF_OFF = "user_preference_off";
  static final String REASON_NO_CONTENT = "no_content_context";
  static final String REASON_SOURCE_UNAVAILABLE = "source_unavailable";
  static final String REASON_PHASE2 = "phase2_deferred";

  private static final int RECENT_LIMIT = 5;

  private final LearningClient learningClient;
  private final SandboxClient sandboxClient;

  public SnapshotAssembler(LearningClient learningClient, SandboxClient sandboxClient) {
    this.learningClient = learningClient;
    this.sandboxClient = sandboxClient;
  }

  public AssemblyResult assemble(long userId, Long contentId, List<String> requestedFields,
      UserContextPreference prefs) {

    Map<String, Object> content = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<FieldUnavailable> unavailable = new ArrayList<>();

    // current_content (learning-svc 풀)
    if (requested(requestedFields, CURRENT_CONTENT)) {
      if (!prefs.isCollectCurrentContent()) {
        unavailable.add(new FieldUnavailable(CURRENT_CONTENT, REASON_PREF_OFF));
      } else if (contentId == null) {
        unavailable.add(new FieldUnavailable(CURRENT_CONTENT, REASON_NO_CONTENT));
      } else {
        Optional<ContentView> view = safeContent(contentId);
        if (view.isPresent()) {
          ContentView c = view.get();
          Map<String, Object> cc = new LinkedHashMap<>();
          cc.put("contentId", c.id());
          cc.put("title", c.title());
          cc.put("track", c.track());
          content.put(CURRENT_CONTENT, cc);
          included.add(CURRENT_CONTENT);
        } else {
          unavailable.add(new FieldUnavailable(CURRENT_CONTENT, REASON_SOURCE_UNAVAILABLE));
        }
      }
    }

    // recent_activity (sandbox-svc 풀) — current_content 토글로 게이팅(별도 pref 없음, MVP)
    if (requested(requestedFields, RECENT_ACTIVITY)) {
      if (!prefs.isCollectCurrentContent()) {
        unavailable.add(new FieldUnavailable(RECENT_ACTIVITY, REASON_PREF_OFF));
      } else {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (RunView r : safeRecent(userId)) {
          Map<String, Object> run = new LinkedHashMap<>();
          run.put("language", r.language());
          run.put("status", r.status());
          runs.add(run);
        }
        content.put(RECENT_ACTIVITY, runs);
        included.add(RECENT_ACTIVITY);
      }
    }

    // active_tags / tag_reputation / current_path / recent_errors → Phase 2 연기(항상 불가)
    deferPhase2(requestedFields, ACTIVE_TAGS, unavailable);
    deferPhase2(requestedFields, TAG_REPUTATION, unavailable);
    deferPhase2(requestedFields, CURRENT_PATH, unavailable);
    deferPhase2(requestedFields, RECENT_ERRORS, unavailable);

    return new AssemblyResult(content, included, unavailable);
  }

  private void deferPhase2(List<String> requestedFields, String field,
      List<FieldUnavailable> unavailable) {
    if (requested(requestedFields, field)) {
      unavailable.add(new FieldUnavailable(field, REASON_PHASE2));
    }
  }

  /** requestedFields 가 비었으면 전체 요청으로 간주, 아니면 포함된 필드만. */
  private boolean requested(List<String> requestedFields, String field) {
    return requestedFields == null || requestedFields.isEmpty() || requestedFields.contains(field);
  }

  private Optional<ContentView> safeContent(long contentId) {
    try {
      return learningClient.getContent(contentId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private List<RunView> safeRecent(long userId) {
    try {
      return sandboxClient.recentByUser(userId, RECENT_LIMIT);
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
