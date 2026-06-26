package ai.devpath.lcs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 영속 학습 맥락 스냅샷. 커밋 후 불변(updated_at 없음, setter 없음).
 * content_snapshot / fields_included 는 JSONB(camelCase) 문자열.
 */
@Entity
@Table(name = "learning_context_snapshots")
public class LearningContextSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "purpose", nullable = false)
  private String purpose;

  @Column(name = "attached_to_type")
  private String attachedToType;

  @Column(name = "attached_to_id")
  private Long attachedToId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content_snapshot", nullable = false)
  private String contentSnapshot;

  @Column(name = "visibility", nullable = false)
  private String visibility;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fields_included", nullable = false)
  private String fieldsIncluded;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LearningContextSnapshot() {
    // JPA
  }

  public LearningContextSnapshot(Long userId, String purpose, String attachedToType,
      Long attachedToId, String contentSnapshot, String visibility, String fieldsIncluded) {
    this.userId = userId;
    this.purpose = purpose;
    this.attachedToType = attachedToType;
    this.attachedToId = attachedToId;
    this.contentSnapshot = contentSnapshot;
    this.visibility = visibility;
    this.fieldsIncluded = fieldsIncluded;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getPurpose() {
    return purpose;
  }

  public String getAttachedToType() {
    return attachedToType;
  }

  public Long getAttachedToId() {
    return attachedToId;
  }

  public String getContentSnapshot() {
    return contentSnapshot;
  }

  public String getVisibility() {
    return visibility;
  }

  public String getFieldsIncluded() {
    return fieldsIncluded;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
