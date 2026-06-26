package ai.devpath.lcs.client;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** learning-svc 내부 조회(게이트웨이 미경유). 현재 콘텐츠 메타. 실패/미존재는 empty(맥락 결손 허용). */
@Component
public class LearningClient {

  private final RestClient restClient;

  public LearningClient(
      @Value("${devpath.learning.base-url:http://localhost:8082}") String baseUrl,
      @Value("${devpath.learning.timeout:PT5S}") Duration timeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** 현재 콘텐츠 조회. 미존재(404)·오류·연결실패 모두 empty 폴백(부분 스냅샷 허용). */
  public Optional<ContentView> getContent(long id) {
    try {
      ContentView view = restClient.get()
          .uri("/internal/contents/{id}", id)
          .retrieve()
          .onStatus(HttpStatusCode::isError, (req, res) -> { /* swallow → null */ })
          .body(ContentView.class);
      return Optional.ofNullable(view);
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }
}
