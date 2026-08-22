package ai.devpath.lcs.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** sandbox-svc 내부 metadata 조회(게이트웨이 미경유). source failure는 명시적으로 구분한다. */
@Component
public class SandboxClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-DevPath-Internal-Token";

  private final RestClient restClient;

  public SandboxClient(
      @Value("${devpath.sandbox.base-url:http://localhost:8085}") String baseUrl,
      @Value("${devpath.sandbox.timeout:PT5S}") Duration timeout,
      @Value("${devpath.auth.internal-token:}") String internalToken) {
    if (internalToken == null || internalToken.isBlank()) {
      throw new IllegalStateException("sandbox internal token is required");
    }
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    JsonMapper strictMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    this.restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(INTERNAL_TOKEN_HEADER, internalToken)
        .requestFactory(factory)
        .configureMessageConverters(converters -> converters.withJsonConverter(
            new JacksonJsonHttpMessageConverter(strictMapper)))
        .build();
  }

  /** 사용자별 최근 N개 metadata. empty Optional은 source failure, present empty는 정상 빈 결과. */
  public Optional<List<RunMetadata>> recentByUser(long userId, int limit) {
    try {
      RunMetadata[] arr = restClient.get()
          .uri(uriBuilder -> uriBuilder.path("/internal/sandbox/sessions/recent/metadata")
              .queryParam("userId", userId)
              .queryParam("limit", limit)
              .build())
          .retrieve()
          .body(RunMetadata[].class);
      return arr == null ? Optional.empty() : Optional.of(List.of(arr));
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }
}
