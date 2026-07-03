package ai.devpath.lcs.config;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 리소스 없음 → 스펙 §3.4 RESOURCE_NOT_FOUND(404). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class NotFoundException extends ApiException {
  public NotFoundException(String msg) {
    super(ErrorCode.RESOURCE_NOT_FOUND, msg);
  }
}
