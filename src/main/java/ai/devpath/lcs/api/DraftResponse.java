package ai.devpath.lcs.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 미리보기 조립 응답. draft 는 Redis TTL 10분. */
public record DraftResponse(
    String draftId,
    Instant expiresAt,
    Map<String, Object> content,
    List<String> fieldsAvailable,
    List<FieldUnavailable> fieldsUnavailable) {
}
