package ai.devpath.lcs.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void recentRequestCarriesExactInternalToken() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("[{\"language\":\"PYTHON\",\"status\":\"COMPLETED\"}]"));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThat(client.recentByUser(42L, 5))
        .contains(List.of(new RunMetadata("PYTHON", "COMPLETED")));
    var request = server.takeRequest();
    assertThat(request.getHeader("X-DevPath-Internal-Token"))
        .isEqualTo("test-internal-token");
    assertThat(request.getPath())
        .isEqualTo("/internal/sandbox/sessions/recent/metadata?userId=42&limit=5");
  }

  @Test
  void missingOrBlankInternalTokenFailsBeforeAnyHttpRequest() {
    for (String token : new String[] {null, "", "  \t"}) {
      assertThatThrownBy(() -> new SandboxClient(
          server.url("/").toString(), Duration.ofSeconds(5), token))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("sandbox internal token is required");
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void notFoundAndServerErrorReturnUnavailableWithoutRawFallback() throws Exception {
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    for (int status : new int[] {404, 503}) {
      server.enqueue(new MockResponse().setResponseCode(status));
      assertThat(client.recentByUser(42L, 5)).isEmpty();
      assertThat(server.takeRequest().getPath())
          .isEqualTo("/internal/sandbox/sessions/recent/metadata?userId=42&limit=5");
    }
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void validEmptyMetadataIsDistinguishedFromAnUnavailableSource() {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("[]"));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThat(client.recentByUser(42L, 5)).contains(List.of());
  }

  @Test
  void rawOrMalformedMetadataFailsClosedWithoutCallingTheLegacyEndpoint() throws Exception {
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("[{\"language\":\"JAVA\",\"status\":\"COMPLETED\","
            + "\"submittedCode\":\"SECRET_CODE\",\"stdout\":\"SECRET_STDOUT\","
            + "\"stderr\":\"SECRET_STDERR\"}]"));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThat(client.recentByUser(42L, 5)).isEmpty();
    assertThat(server.takeRequest().getPath())
        .isEqualTo("/internal/sandbox/sessions/recent/metadata?userId=42&limit=5");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }
}
