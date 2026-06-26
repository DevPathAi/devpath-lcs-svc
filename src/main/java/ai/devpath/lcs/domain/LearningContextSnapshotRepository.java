package ai.devpath.lcs.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningContextSnapshotRepository
    extends JpaRepository<LearningContextSnapshot, Long> {
}
