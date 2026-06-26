package ai.devpath.lcs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 사용자별 맥락 수집 프라이버시 토글(가변). user_id = jwt subject(Long).
 * recent_errors 는 가장 민감 → 기본 OFF(Phase 2 활성).
 */
@Entity
@Table(name = "user_context_preferences")
public class UserContextPreference {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "collect_current_content", nullable = false)
  private boolean collectCurrentContent = true;

  @Column(name = "collect_learning_path", nullable = false)
  private boolean collectLearningPath = true;

  @Column(name = "collect_active_tags", nullable = false)
  private boolean collectActiveTags = true;

  @Column(name = "collect_recent_errors", nullable = false)
  private boolean collectRecentErrors = false;

  @Column(name = "collect_tag_reputation", nullable = false)
  private boolean collectTagReputation = true;

  @Column(name = "collect_level", nullable = false)
  private boolean collectLevel = true;

  @Column(name = "default_visibility", nullable = false)
  private String defaultVisibility = "answerers_only";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserContextPreference() {
    // JPA
  }

  public UserContextPreference(Long userId) {
    this.userId = userId;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getUserId() {
    return userId;
  }

  public boolean isCollectCurrentContent() {
    return collectCurrentContent;
  }

  public void setCollectCurrentContent(boolean collectCurrentContent) {
    this.collectCurrentContent = collectCurrentContent;
  }

  public boolean isCollectLearningPath() {
    return collectLearningPath;
  }

  public void setCollectLearningPath(boolean collectLearningPath) {
    this.collectLearningPath = collectLearningPath;
  }

  public boolean isCollectActiveTags() {
    return collectActiveTags;
  }

  public void setCollectActiveTags(boolean collectActiveTags) {
    this.collectActiveTags = collectActiveTags;
  }

  public boolean isCollectRecentErrors() {
    return collectRecentErrors;
  }

  public void setCollectRecentErrors(boolean collectRecentErrors) {
    this.collectRecentErrors = collectRecentErrors;
  }

  public boolean isCollectTagReputation() {
    return collectTagReputation;
  }

  public void setCollectTagReputation(boolean collectTagReputation) {
    this.collectTagReputation = collectTagReputation;
  }

  public boolean isCollectLevel() {
    return collectLevel;
  }

  public void setCollectLevel(boolean collectLevel) {
    this.collectLevel = collectLevel;
  }

  public String getDefaultVisibility() {
    return defaultVisibility;
  }

  public void setDefaultVisibility(String defaultVisibility) {
    this.defaultVisibility = defaultVisibility;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
