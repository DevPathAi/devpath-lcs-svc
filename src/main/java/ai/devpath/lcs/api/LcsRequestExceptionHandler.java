package ai.devpath.lcs.api;

import ai.devpath.shared.error.ErrorCode;
import ai.devpath.shared.error.ErrorResponse;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps strict JSON decoding failures without reflecting user-supplied context. */
@RestControllerAdvice(assignableTypes = LcsController.class)
public class LcsRequestExceptionHandler {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> malformedRequest() {
    return ResponseEntity.badRequest().body(ErrorResponse.of(
        ErrorCode.VALIDATION_FAILED, "malformed request", null, Instant.now().toString()));
  }
}
