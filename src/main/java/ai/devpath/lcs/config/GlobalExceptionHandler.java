package ai.devpath.lcs.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<String> notFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<String> forbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
  }
}
