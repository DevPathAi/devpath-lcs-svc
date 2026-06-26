package ai.devpath.lcs.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserContextPreferenceRepository
    extends JpaRepository<UserContextPreference, Long> {
}
