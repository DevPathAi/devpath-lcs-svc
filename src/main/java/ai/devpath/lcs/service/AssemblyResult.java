package ai.devpath.lcs.service;

import ai.devpath.lcs.api.FieldUnavailable;
import java.util.List;
import java.util.Map;

/** 조립 결과: 포함된 맥락(content) + 포함 필드 목록 + 불가 필드(사유). */
public record AssemblyResult(
    Map<String, Object> content,
    List<String> fieldsIncluded,
    List<FieldUnavailable> fieldsUnavailable) {
}
