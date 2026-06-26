package ai.devpath.lcs.draft;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 미리보기 draft 저장소 추상화(Redis 구현 + 테스트 인메모리 fake). */
public interface DraftStore {

  /** draft 저장 후 토큰(draftId) 반환. */
  String save(long userId, Map<String, Object> content, List<String> fieldsIncluded);

  /** draftId 로 draft 조회(만료/미존재면 empty). */
  Optional<Draft> get(String draftId);

  /** draft 삭제(커밋 후). */
  void delete(String draftId);
}
