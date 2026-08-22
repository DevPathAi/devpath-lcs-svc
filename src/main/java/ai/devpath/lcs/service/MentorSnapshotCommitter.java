package ai.devpath.lcs.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic Mentor snapshot insert/replay keyed by the canonical Redis draft identity. */
@Component
public class MentorSnapshotCommitter {

  private final JdbcTemplate jdbc;

  public MentorSnapshotCommitter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MentorCommitResult commit(long userId, String sourceDraftId,
      String contentSnapshot, String fieldsIncluded) {
    List<MentorCommitResult> inserted = jdbc.query(
        "INSERT INTO learning_context_snapshots"
            + "(user_id,purpose,attached_to_type,attached_to_id,content_snapshot,visibility,"
            + "fields_included,source_draft_id) VALUES"
            + "(?, 'mentor_prompt', NULL, NULL, CAST(? AS jsonb), 'private', CAST(? AS jsonb), ?) "
            + "ON CONFLICT (source_draft_id) DO NOTHING "
            + "RETURNING id,user_id,purpose,visibility",
        (rs, rowNum) -> new MentorCommitResult(
            rs.getLong("id"), rs.getLong("user_id"),
            rs.getString("purpose"), rs.getString("visibility")),
        userId, contentSnapshot, fieldsIncluded, sourceDraftId);
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }
    return jdbc.query(
        "SELECT id,user_id,purpose,visibility FROM learning_context_snapshots "
            + "WHERE source_draft_id=?",
        (rs, rowNum) -> new MentorCommitResult(
            rs.getLong("id"), rs.getLong("user_id"),
            rs.getString("purpose"), rs.getString("visibility")),
        sourceDraftId).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("committed mentor snapshot unavailable"));
  }
}
