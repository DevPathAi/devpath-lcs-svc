package ai.devpath.lcs.release;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/release/lcs/{candidate}/{runKey}")
@ConditionalOnProperty(name = "devpath.release.enabled", havingValue = "true")
public class LcsReleaseController {
  private final ReleasePreviewRegistry release;

  public LcsReleaseController(ReleasePreviewRegistry release) {
    this.release = release;
  }

  @GetMapping("/checkpoints/{checkpoint}")
  public Map<String, Object> checkpoint(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String checkpoint) {
    return Map.of("passed", release.checkpoint(candidate, runKey, checkpoint));
  }
}
