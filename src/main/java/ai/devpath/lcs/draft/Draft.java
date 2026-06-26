package ai.devpath.lcs.draft;

import java.util.List;
import java.util.Map;

/** Redis 에 보관되는 미리보기 draft(소유자 + 조립 결과). TTL 10분. */
public record Draft(long userId, Map<String, Object> content, List<String> fieldsIncluded) {
}
