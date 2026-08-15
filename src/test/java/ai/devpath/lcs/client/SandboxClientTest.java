package ai.devpath.lcs.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
        .setBody("[{\"id\":7,\"userId\":42,\"language\":\"PYTHON\","
            + "\"contentId\":null,\"submittedCode\":\"print(1)\",\"stdout\":\"1\","
            + "\"stderr\":\"\",\"exitCode\":0,\"status\":\"COMPLETED\"}]"));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThat(client.recentByUser(42L, 5)).hasSize(1);
    var request = server.takeRequest();
    assertThat(request.getHeader("X-DevPath-Internal-Token"))
        .isEqualTo("test-internal-token");
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
  void networkErrorStillReturnsEmptyAfterTokenValidation() {
    server.enqueue(new MockResponse().setResponseCode(500));
    var client = new SandboxClient(
        server.url("/").toString(), Duration.ofSeconds(5), "test-internal-token");

    assertThat(client.recentByUser(42L, 5)).isEmpty();
  }
}
