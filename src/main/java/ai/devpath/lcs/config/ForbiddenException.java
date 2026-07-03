package ai.devpath.lcs.config;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 권한 없음 → 스펙 §3.4 FORBIDDEN(403). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class ForbiddenException extends ApiException {
  public ForbiddenException(String msg) {
    super(ErrorCode.FORBIDDEN, msg);
  }
}
