package ai.devpath.lcs.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningContextSnapshotRepository
    extends JpaRepository<LearningContextSnapshot, Long> {

  /** 질문에 첨부된 커밋 스냅샷(불변·1건 가정, 다건이면 최신). 인덱스 (attached_to_type, attached_to_id). */
  Optional<LearningContextSnapshot> findFirstByAttachedToTypeAndAttachedToIdOrderByCreatedAtDesc(
      String attachedToType, Long attachedToId);
}
