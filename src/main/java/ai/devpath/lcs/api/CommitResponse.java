package ai.devpath.lcs.api;

/** 영속 응답. 커밋 후 불변. */
public record CommitResponse(Long snapshotId, String status, boolean immutable) {
}
