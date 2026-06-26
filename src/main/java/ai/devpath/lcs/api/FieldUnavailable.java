package ai.devpath.lcs.api;

/** 조립 불가 필드 + 사유(user_preference_off / no_content_context / source_unavailable / phase2_deferred). */
public record FieldUnavailable(String field, String reason) {
}
