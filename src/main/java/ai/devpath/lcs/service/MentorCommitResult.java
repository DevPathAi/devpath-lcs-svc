package ai.devpath.lcs.service;

/** Result of the DB-authoritative source-draft insert/replay fence. */
public record MentorCommitResult(
    long snapshotId,
    long userId,
    String purpose,
    String visibility) {
}
