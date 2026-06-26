package ai.devpath.lcs.api;

/** draft 영속 요청. attachedToId = community_questions.post_id 논리참조. */
public record CommitRequest(String attachedToType, Long attachedToId, String visibility) {
}
