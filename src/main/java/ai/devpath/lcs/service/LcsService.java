package ai.devpath.lcs.service;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.CommitResponse;
import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import ai.devpath.lcs.api.PreferencesView;
import ai.devpath.lcs.api.SnapshotView;
import ai.devpath.lcs.config.ForbiddenException;
import ai.devpath.lcs.config.NotFoundException;
import ai.devpath.lcs.domain.LearningContextSnapshot;
import ai.devpath.lcs.domain.LearningContextSnapshotRepository;
import ai.devpath.lcs.domain.UserContextPreference;
import ai.devpath.lcs.domain.UserContextPreferenceRepository;
import ai.devpath.lcs.draft.Draft;
import ai.devpath.lcs.draft.DraftStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** LCS 코어 서비스: draft 조립/저장 · commit 영속(불변) · 조회(인가) · 프라이버시 preferences. */
@Service
public class LcsService {

  private static final String DEFAULT_VISIBILITY = "answerers_only";
  private static final String PURPOSE_QUESTION = "question_attachment";
  private static final String ATTACHED_TYPE_QUESTION = "question";
  private static final Duration DRAFT_TTL = Duration.ofMinutes(10);

  private final SnapshotAssembler assembler;
  private final DraftStore draftStore;
  private final LearningContextSnapshotRepository snapshots;
  private final UserContextPreferenceRepository preferences;
  private final JsonMapper jsonMapper;

  public LcsService(SnapshotAssembler assembler, DraftStore draftStore,
      LearningContextSnapshotRepository snapshots, UserContextPreferenceRepository preferences,
      JsonMapper jsonMapper) {
    this.assembler = assembler;
    this.draftStore = draftStore;
    this.snapshots = snapshots;
    this.preferences = preferences;
    this.jsonMapper = jsonMapper;
  }

  /** 미리보기 조립 → Redis draft 저장(소유자 = userId). */
  public DraftResponse draft(long userId, DraftRequest req) {
    UserContextPreference prefs = preferences.findById(userId)
        .orElseGet(() -> new UserContextPreference(userId));
    AssemblyResult result =
        assembler.assemble(userId, req.contentId(), req.requestedFields(), prefs);
    String draftId = draftStore.save(userId, result.content(), result.fieldsIncluded());
    Instant expiresAt = Instant.now().plus(DRAFT_TTL);
    return new DraftResponse(draftId, expiresAt, result.content(),
        result.fieldsIncluded(), result.fieldsUnavailable());
  }

  /** draft 영속(불변). 소유자 본인만. */
  @Transactional
  public CommitResponse commit(long userId, String draftId, CommitRequest req) {
    Draft draft = draftStore.get(draftId)
        .orElseThrow(() -> new NotFoundException("draft not found: " + draftId));
    if (draft.userId() != userId) {
      throw new ForbiddenException("not the draft owner");
    }
    String visibility = req.visibility() == null ? DEFAULT_VISIBILITY : req.visibility();
    LearningContextSnapshot snapshot = new LearningContextSnapshot(
        userId,
        PURPOSE_QUESTION,
        req.attachedToType(),
        req.attachedToId(),
        toJson(draft.content()),
        visibility,
        toJson(draft.fieldsIncluded()));
    LearningContextSnapshot saved = snapshots.save(snapshot);
    draftStore.delete(draftId);
    return new CommitResponse(saved.getId(), "committed", true);
  }

  /** 답변자 조회 + 인가(public/answerers_only=로그인 전체, private=작성자 본인). */
  @Transactional(readOnly = true)
  public SnapshotView getSnapshot(long userId, long id) {
    LearningContextSnapshot snapshot = snapshots.findById(id)
        .orElseThrow(() -> new NotFoundException("snapshot not found: " + id));
    if (!canView(snapshot, userId)) {
      throw new ForbiddenException("not allowed to view snapshot: " + id);
    }
    Map<String, Object> content = fromJson(snapshot.getContentSnapshot());
    return new SnapshotView(snapshot.getId(), snapshot.getCreatedAt(), content, "answerer");
  }

  /**
   * 질문에 첨부된 커밋 스냅샷 역조회(답변자 패널용). community 무변경 유지를 위해 questionId로 조회.
   * 인가는 {@link #getSnapshot}과 동일(public/answerers_only=로그인 전체, private=작성자 본인).
   */
  @Transactional(readOnly = true)
  public SnapshotView getSnapshotByQuestion(long userId, long questionId) {
    LearningContextSnapshot snapshot =
        snapshots
            .findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc(
                ATTACHED_TYPE_QUESTION, questionId)
            .orElseThrow(() -> new NotFoundException("no snapshot for question: " + questionId));
    if (!canView(snapshot, userId)) {
      throw new ForbiddenException("not allowed to view snapshot for question: " + questionId);
    }
    Map<String, Object> content = fromJson(snapshot.getContentSnapshot());
    return new SnapshotView(snapshot.getId(), snapshot.getCreatedAt(), content, "answerer");
  }

  private boolean canView(LearningContextSnapshot snapshot, long userId) {
    String visibility = snapshot.getVisibility();
    if ("public".equals(visibility) || "answerers_only".equals(visibility)) {
      return true;
    }
    if ("private".equals(visibility)) {
      return snapshot.getUserId() != null && snapshot.getUserId() == userId;
    }
    return false;
  }

  /** 프라이버시 조회(없으면 기본값). */
  @Transactional(readOnly = true)
  public PreferencesView getPreferences(long userId) {
    UserContextPreference prefs = preferences.findById(userId)
        .orElseGet(() -> new UserContextPreference(userId));
    return toView(prefs);
  }

  /** 프라이버시 upsert. */
  @Transactional
  public PreferencesView putPreferences(long userId, PreferencesView req) {
    UserContextPreference prefs = preferences.findById(userId)
        .orElseGet(() -> new UserContextPreference(userId));
    prefs.setCollectCurrentContent(req.collectCurrentContent());
    prefs.setCollectLearningPath(req.collectLearningPath());
    prefs.setCollectActiveTags(req.collectActiveTags());
    prefs.setCollectRecentErrors(req.collectRecentErrors());
    prefs.setCollectTagReputation(req.collectTagReputation());
    prefs.setCollectLevel(req.collectLevel());
    prefs.setDefaultVisibility(
        req.defaultVisibility() == null ? DEFAULT_VISIBILITY : req.defaultVisibility());
    return toView(preferences.save(prefs));
  }

  private PreferencesView toView(UserContextPreference p) {
    return new PreferencesView(
        p.isCollectCurrentContent(),
        p.isCollectLearningPath(),
        p.isCollectActiveTags(),
        p.isCollectRecentErrors(),
        p.isCollectTagReputation(),
        p.isCollectLevel(),
        p.getDefaultVisibility());
  }

  private String toJson(Object value) {
    return jsonMapper.writeValueAsString(value);
  }

  private Map<String, Object> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }
}
