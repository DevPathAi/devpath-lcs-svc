package ai.devpath.lcs;

import ai.devpath.shared.error.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ApiExceptionHandler.class) // 스펙 §3.4 공통 에러 envelope(공용 advice)
public class LcsApplication {

  public static void main(String[] args) {
    SpringApplication.run(LcsApplication.class, args);
  }
}
