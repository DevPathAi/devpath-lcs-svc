package ai.devpath.lcs.client;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** sandbox-svc 내부 조회(게이트웨이 미경유). 사용자별 최근 실행. 실패는 빈 리스트(맥락 결손 허용). */
@Component
public class SandboxClient {

  private final RestClient restClient;

  public SandboxClient(
      @Value("${devpath.sandbox.base-url:http://localhost:8085}") String baseUrl,
      @Value("${devpath.sandbox.timeout:PT5S}") Duration timeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** 사용자별 최근 N개 실행. 실패는 빈 리스트(부분 스냅샷 허용). */
  public List<RunView> recentByUser(long userId, int limit) {
    try {
      RunView[] arr = restClient.get()
          .uri(uriBuilder -> uriBuilder.path("/internal/sandbox/sessions/recent")
              .queryParam("userId", userId)
              .queryParam("limit", limit)
              .build())
          .retrieve()
          .body(RunView[].class);
      return arr == null ? List.of() : List.of(arr);
    } catch (RestClientException e) {
      return List.of();
    }
  }
}
