package ai.devpath.lcs.service;

import ai.devpath.lcs.api.FieldUnavailable;
import ai.devpath.lcs.client.ContentView;
import ai.devpath.lcs.client.LearningClient;
import ai.devpath.lcs.client.RunMetadata;
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

  static final String CURRENT_CONTENT = SnapshotPolicy.CURRENT_CONTENT;
  static final String RECENT_ACTIVITY = SnapshotPolicy.RECENT_ACTIVITY;
  static final String ACTIVE_TAGS = SnapshotPolicy.ACTIVE_TAGS;
  static final String TAG_REPUTATION = SnapshotPolicy.TAG_REPUTATION;
  static final String CURRENT_PATH = SnapshotPolicy.CURRENT_PATH;
  static final String RECENT_ERRORS = SnapshotPolicy.RECENT_ERRORS;
  static final String CURRENT_CODE = SnapshotPolicy.CURRENT_CODE;
  static final String RECENT_OUTPUT = SnapshotPolicy.RECENT_OUTPUT;
  static final String REVIEW_SUMMARY = SnapshotPolicy.REVIEW_SUMMARY;

  static final String REASON_PREF_OFF = "user_preference_off";
  static final String REASON_NO_CONTENT = "no_content_context";
  static final String REASON_SOURCE_UNAVAILABLE = "source_unavailable";
  static final String REASON_PHASE2 = "phase2_deferred";
  static final String REASON_REQUEST_CONTEXT_MISSING = "request_context_missing";

  private static final int RECENT_LIMIT = 5;

  private final LearningClient learningClient;
  private final SandboxClient sandboxClient;

  public SnapshotAssembler(LearningClient learningClient, SandboxClient sandboxClient) {
    this.learningClient = learningClient;
    this.sandboxClient = sandboxClient;
  }

  public AssemblyResult assemble(long userId, Long contentId, List<String> requestedFields,
      UserContextPreference prefs) {
    List<String> normalized = SnapshotPolicy.normalizeRequestedFields(
        SnapshotPolicy.QUESTION_ATTACHMENT, requestedFields);
    return assemble(SnapshotPolicy.QUESTION_ATTACHMENT,
        userId, contentId, normalized, Map.of(), prefs);
  }

  public AssemblyResult assemble(String purpose, long userId, Long contentId,
      List<String> requestedFields, Map<String, Object> requestContext,
      UserContextPreference prefs) {

    Map<String, Object> validatedContext =
        SnapshotPolicy.validateRequestContext(purpose, requestedFields, requestContext);

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
          if (SnapshotPolicy.MENTOR_PROMPT.equals(purpose)
              && (c.id() != contentId
                  || !SnapshotPolicy.isValidMentorSourceField(CURRENT_CONTENT, cc))) {
            unavailable.add(new FieldUnavailable(CURRENT_CONTENT, REASON_SOURCE_UNAVAILABLE));
          } else {
            content.put(CURRENT_CONTENT, cc);
            included.add(CURRENT_CONTENT);
          }
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
        Optional<List<RunMetadata>> recent = safeRecent(userId);
        if (SnapshotPolicy.MENTOR_PROMPT.equals(purpose) && recent.isEmpty()) {
          unavailable.add(new FieldUnavailable(RECENT_ACTIVITY, REASON_SOURCE_UNAVAILABLE));
        } else {
          List<Map<String, Object>> runs = new ArrayList<>();
          for (RunMetadata r : recent.orElseGet(List::of)) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("language", r.language());
            run.put("status", r.status());
            runs.add(run);
          }
          if (SnapshotPolicy.MENTOR_PROMPT.equals(purpose)
              && !SnapshotPolicy.isValidMentorSourceField(RECENT_ACTIVITY, runs)) {
            unavailable.add(new FieldUnavailable(RECENT_ACTIVITY, REASON_SOURCE_UNAVAILABLE));
          } else {
            content.put(RECENT_ACTIVITY, runs);
            included.add(RECENT_ACTIVITY);
          }
        }
      }
    }

    // active_tags / tag_reputation / current_path / recent_errors → Phase 2 연기(항상 불가)
    deferPhase2(requestedFields, ACTIVE_TAGS, unavailable);
    deferPhase2(requestedFields, TAG_REPUTATION, unavailable);
    deferPhase2(requestedFields, CURRENT_PATH, unavailable);

    includeRequestContext(
        requestedFields, validatedContext, CURRENT_CODE, content, included, unavailable);
    if (requested(requestedFields, RECENT_ERRORS)) {
      if (!validatedContext.containsKey(RECENT_ERRORS)) {
        unavailable.add(new FieldUnavailable(RECENT_ERRORS, REASON_PHASE2));
      } else if (!prefs.isCollectRecentErrors()) {
        unavailable.add(new FieldUnavailable(RECENT_ERRORS, REASON_PREF_OFF));
      } else {
        content.put(RECENT_ERRORS, validatedContext.get(RECENT_ERRORS));
        included.add(RECENT_ERRORS);
      }
    }
    includeRequestContext(
        requestedFields, validatedContext, RECENT_OUTPUT, content, included, unavailable);
    includeRequestContext(
        requestedFields, validatedContext, REVIEW_SUMMARY, content, included, unavailable);

    return new AssemblyResult(content, included, unavailable);
  }

  private void includeRequestContext(List<String> requestedFields,
      Map<String, Object> requestContext, String field, Map<String, Object> content,
      List<String> included, List<FieldUnavailable> unavailable) {
    if (!requested(requestedFields, field)) {
      return;
    }
    if (!requestContext.containsKey(field)) {
      unavailable.add(new FieldUnavailable(field, REASON_REQUEST_CONTEXT_MISSING));
      return;
    }
    content.put(field, requestContext.get(field));
    included.add(field);
  }

  private void deferPhase2(List<String> requestedFields, String field,
      List<FieldUnavailable> unavailable) {
    if (requested(requestedFields, field)) {
      unavailable.add(new FieldUnavailable(field, REASON_PHASE2));
    }
  }

  /** Callers normalize empty selection according to purpose before assembly. */
  private boolean requested(List<String> requestedFields, String field) {
    return requestedFields.contains(field);
  }

  private Optional<ContentView> safeContent(long contentId) {
    try {
      return learningClient.getContent(contentId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Optional<List<RunMetadata>> safeRecent(long userId) {
    try {
      return sandboxClient.recentByUser(userId, RECENT_LIMIT);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
