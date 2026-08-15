package ai.devpath.lcs.draft;

import java.util.List;
import java.util.Map;

/** Redis 에 보관되는 미리보기 draft(소유자 + 조립 결과). TTL 10분. */
public record Draft(
    long userId,
    String purpose,
    Long contentId,
    List<String> requestedFields,
    String forcedVisibility,
    Map<String, Object> content,
    List<String> fieldsIncluded) {

  public Draft {
    // Drafts created before the purpose-aware rollout deserialize with null metadata.
    // Treat them only as the historical Community policy; never promote them to Mentor.
    purpose = purpose == null ? "question_attachment" : purpose;
    requestedFields = requestedFields == null ? List.of() : List.copyOf(requestedFields);
    content = content == null ? Map.of() : Map.copyOf(content);
    fieldsIncluded = fieldsIncluded == null ? List.of() : List.copyOf(fieldsIncluded);
  }

  public static Draft legacyQuestion(
      long userId, Map<String, Object> content, List<String> fieldsIncluded) {
    return new Draft(userId, "question_attachment", null, List.of(), null,
        content, fieldsIncluded);
  }
}
