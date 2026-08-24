package ai.devpath.lcs.release;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.lcs.config.InternalApiAuthenticationFilter;
import ai.devpath.lcs.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LcsReleaseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "devpath.release.enabled=true",
    "devpath.auth.internal-token=release-internal-token"
})
class LcsReleaseSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean ReleasePreviewRegistry release;

  @Test
  void previewCheckpointRequiresTheExistingWorkloadCredential() throws Exception {
    String candidate = "a".repeat(64);
    String run = "R".repeat(43);
    String checkpoint = "private-mentor-prompt-preview";
    String path = "/internal/release/lcs/" + candidate + "/" + run
        + "/checkpoints/" + checkpoint;
    when(release.checkpoint(candidate, run, checkpoint)).thenReturn(true);

    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header(
            InternalApiAuthenticationFilter.HEADER, "release-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true));
  }
}
