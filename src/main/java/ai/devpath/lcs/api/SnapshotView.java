package ai.devpath.lcs.api;

import java.time.Instant;
import java.util.Map;

/** 답변자 조회 응답. renderedFor = "answerer". */
public record SnapshotView(Long id, Instant createdAt, Map<String, Object> content, String renderedFor) {
}
