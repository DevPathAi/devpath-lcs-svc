package ai.devpath.lcs.service;

import ai.devpath.lcs.api.CommitRequest;
import ai.devpath.lcs.api.CommitResponse;
import ai.devpath.lcs.api.DraftRequest;
import ai.devpath.lcs.api.DraftResponse;
import ai.devpath.lcs.api.MentorSnapshotView;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** LCS 코어 서비스: draft 조립/저장 · commit 영속(불변) · 조회(인가) · 프라이버시 preferences. */
@Service
public class LcsService {

  private static final String DEFAULT_VISIBILITY = "answerers_only";
  private static final String PURPOSE_QUESTION = SnapshotPolicy.QUESTION_ATTACHMENT;
  private static final String PURPOSE_MENTOR = SnapshotPolicy.MENTOR_PROMPT;
  private static final String VISIBILITY_PRIVATE = SnapshotPolicy.PRIVATE;
  private static final String ATTACHED_TYPE_QUESTION = "question";
  private static final Duration DRAFT_TTL = Duration.ofMinutes(10);
  private static final Pattern DRAFT_ID = Pattern.compile(
      "^snap_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private final SnapshotAssembler assembler;
  private final DraftStore draftStore;
  private final LearningContextSnapshotRepository snapshots;
  private final MentorSnapshotCommitter mentorCommitter;
  private final UserContextPreferenceRepository preferences;
  private final JsonMapper jsonMapper;

  public LcsService(SnapshotAssembler assembler, DraftStore draftStore,
      LearningContextSnapshotRepository snapshots, MentorSnapshotCommitter mentorCommitter,
      UserContextPreferenceRepository preferences, JsonMapper jsonMapper) {
    this.assembler = assembler;
    this.draftStore = draftStore;
    this.snapshots = snapshots;
    this.mentorCommitter = mentorCommitter;
    this.preferences = preferences;
    this.jsonMapper = jsonMapper;
  }

  /** 미리보기 조립 → Redis draft 저장(소유자 = userId). */
  public DraftResponse draft(long userId, DraftRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("draft request is required");
    }
    String purpose = SnapshotPolicy.normalizePurpose(req.purpose());
    if (req.contentId() != null && req.contentId() <= 0) {
      throw new IllegalArgumentException("contentId must be positive");
    }
    List<String> requestedFields =
        SnapshotPolicy.normalizeRequestedFields(purpose, req.requestedFields());
    Map<String, Object> requestContext =
        SnapshotPolicy.validateRequestContext(purpose, requestedFields, req.requestContext());
    UserContextPreference prefs = preferences.findById(userId)
        .orElseGet(() -> new UserContextPreference(userId));
    AssemblyResult result =
        assembler.assemble(
            purpose, userId, req.contentId(), requestedFields, requestContext, prefs);
    String forcedVisibility = PURPOSE_MENTOR.equals(purpose) ? VISIBILITY_PRIVATE : null;
    Draft draft = new Draft(userId, purpose, req.contentId(), requestedFields, forcedVisibility,
        result.content(), result.fieldsIncluded());
    String draftId = draftStore.save(draft);
    Instant expiresAt = Instant.now().plus(DRAFT_TTL);
    return new DraftResponse(draftId, expiresAt, result.content(),
        result.fieldsIncluded(), result.fieldsUnavailable());
  }

  /** draft 영속(불변). 소유자 본인만. */
  @Transactional
  public CommitResponse commit(long userId, String draftId, CommitRequest req) {
    requireCanonicalDraftId(draftId);
    CommitRequest commitRequest = requireCommitRequest(req);

    var committed = snapshots.findBySourceDraftId(draftId);
    if (committed.isPresent()) {
      LearningContextSnapshot snapshot = committed.orElseThrow();
      requireMentorIdentity(snapshot.getId(), snapshot.getUserId(), snapshot.getPurpose(),
          snapshot.getVisibility(), userId);
      rejectMentorCommitOverride(commitRequest);
      deleteMentorDraftBestEffort(draftId);
      return committed(snapshot.getId());
    }

    Draft draft = draftStore.get(draftId)
        .orElseThrow(() -> new NotFoundException("draft unavailable"));
    if (draft.userId() != userId) {
      throw new ForbiddenException("snapshot unavailable");
    }
    if (PURPOSE_MENTOR.equals(draft.purpose())) {
      return commitMentor(userId, draftId, commitRequest, draft);
    }
    if (!PURPOSE_QUESTION.equals(draft.purpose())) {
      throw new ForbiddenException("snapshot unavailable");
    }
    String visibility =
        commitRequest.visibility() == null ? DEFAULT_VISIBILITY : commitRequest.visibility();
    LearningContextSnapshot snapshot = new LearningContextSnapshot(
        userId,
        PURPOSE_QUESTION,
        commitRequest.attachedToType(),
        commitRequest.attachedToId(),
        toJson(draft.content()),
        visibility,
        toJson(draft.fieldsIncluded()));
    LearningContextSnapshot saved = snapshots.save(snapshot);
    draftStore.delete(draftId);
    return committed(saved.getId());
  }

  private CommitResponse commitMentor(
      long userId, String draftId, CommitRequest req, Draft draft) {
    rejectMentorCommitOverride(req);
    validateMentorDraft(draft);
    MentorCommitResult result = mentorCommitter.commit(
        userId, draftId, toJson(draft.content()), toJson(draft.fieldsIncluded()));
    requireMentorIdentity(result.snapshotId(), result.userId(), result.purpose(),
        result.visibility(), userId);
    deleteMentorDraftBestEffort(draftId);
    return committed(result.snapshotId());
  }

  private void validateMentorDraft(Draft draft) {
    try {
      List<String> normalized =
          SnapshotPolicy.normalizeRequestedFields(PURPOSE_MENTOR, draft.requestedFields());
      if (!VISIBILITY_PRIVATE.equals(draft.forcedVisibility())
          || !normalized.equals(draft.requestedFields())
          || (draft.contentId() != null && draft.contentId() <= 0)
          || !new HashSet<>(draft.requestedFields()).containsAll(draft.fieldsIncluded())
          || draft.fieldsIncluded().size() != new HashSet<>(draft.fieldsIncluded()).size()
          || !draft.content().keySet().equals(new HashSet<>(draft.fieldsIncluded()))) {
        throw new IllegalArgumentException("invalid mentor draft binding");
      }
      SnapshotPolicy.validateCommittedMentorContent(draft.fieldsIncluded(), draft.content());
      if (draft.content().containsKey(SnapshotPolicy.CURRENT_CONTENT)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> currentContent =
            (Map<String, Object>) draft.content().get(SnapshotPolicy.CURRENT_CONTENT);
        long assembledContentId = ((Number) currentContent.get("contentId")).longValue();
        if (draft.contentId() == null || draft.contentId() != assembledContentId) {
          throw new IllegalArgumentException("current content does not match the draft request");
        }
      }
    } catch (RuntimeException e) {
      throw new ForbiddenException("snapshot unavailable");
    }
  }

  private void rejectMentorCommitOverride(CommitRequest req) {
    if (req.attachedToType() != null || req.attachedToId() != null || req.visibility() != null) {
      throw new IllegalArgumentException("mentor commit policy cannot be overridden");
    }
  }

  private void requireMentorIdentity(Long snapshotId, Long ownerId, String purpose,
      String visibility, long expectedOwner) {
    if (snapshotId == null || ownerId == null || ownerId != expectedOwner
        || !PURPOSE_MENTOR.equals(purpose) || !VISIBILITY_PRIVATE.equals(visibility)) {
      throw new ForbiddenException("mentor snapshot unavailable");
    }
  }

  private void deleteMentorDraftBestEffort(String draftId) {
    try {
      draftStore.delete(draftId);
    } catch (RuntimeException ignored) {
      // The committed row is the replay authority. Redis cleanup is best effort.
    }
  }

  private CommitResponse committed(long snapshotId) {
    return new CommitResponse(snapshotId, "committed", true);
  }

  private CommitRequest requireCommitRequest(CommitRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("commit request is required");
    }
    return req;
  }

  private void requireCanonicalDraftId(String draftId) {
    if (draftId == null || !DRAFT_ID.matcher(draftId).matches()) {
      throw new IllegalArgumentException("invalid draft id");
    }
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

  /** Strict owner-scoped contract consumed by the Mentor service before provider/SSE work. */
  @Transactional(readOnly = true)
  public MentorSnapshotView consumeMentorSnapshot(long userId, long id) {
    if (id <= 0) {
      throw mentorUnavailable();
    }
    LearningContextSnapshot snapshot = snapshots
        .findByIdAndUserIdAndPurposeAndVisibility(
            id, userId, PURPOSE_MENTOR, VISIBILITY_PRIVATE)
        .orElseThrow(this::mentorUnavailable);
    try {
      Map<String, Object> content = fromJson(snapshot.getContentSnapshot());
      List<String> fields = fromJsonList(snapshot.getFieldsIncluded());
      if (fields.size() != new HashSet<>(fields).size()
          || !content.keySet().equals(new HashSet<>(fields))) {
        throw mentorUnavailable();
      }
      SnapshotPolicy.validateCommittedMentorContent(fields, content);
      return new MentorSnapshotView(snapshot.getId(), PURPOSE_MENTOR, VISIBILITY_PRIVATE,
          List.copyOf(fields), Collections.unmodifiableMap(content));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      throw mentorUnavailable();
    }
  }

  private NotFoundException mentorUnavailable() {
    return new NotFoundException("mentor snapshot unavailable");
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

  private List<String> fromJsonList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return jsonMapper.readValue(json, new TypeReference<List<String>>() {});
  }
}
