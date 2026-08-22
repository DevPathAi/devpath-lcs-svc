package ai.devpath.lcs.api;

import java.util.List;
import java.util.Map;

/** 스냅샷 미리보기 조립 요청. contentId 는 nullable(현재 콘텐츠 없을 수 있음). */
public record DraftRequest(
    String purpose,
    Long contentId,
    List<String> requestedFields,
    Map<String, Object> requestContext) {

  /** Existing Community clients do not send one-request context. */
  public DraftRequest(String purpose, Long contentId, List<String> requestedFields) {
    this(purpose, contentId, requestedFields, null);
  }
}
