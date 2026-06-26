package ai.devpath.lcs.api;

import ai.devpath.lcs.service.LcsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lcs")
public class LcsController {

  private final LcsService lcsService;

  public LcsController(LcsService lcsService) {
    this.lcsService = lcsService;
  }

  @PostMapping("/snapshots/draft")
  public ResponseEntity<DraftResponse> draft(
      @AuthenticationPrincipal Jwt jwt, @RequestBody DraftRequest req) {
    return ResponseEntity.ok(lcsService.draft(uid(jwt), req));
  }

  @PostMapping("/snapshots/{draftId}/commit")
  public ResponseEntity<CommitResponse> commit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String draftId,
      @RequestBody CommitRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(lcsService.commit(uid(jwt), draftId, req));
  }

  @GetMapping("/snapshots/{id}")
  public ResponseEntity<SnapshotView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    return ResponseEntity.ok(lcsService.getSnapshot(uid(jwt), id));
  }

  @GetMapping("/snapshots/by-question/{questionId}")
  public ResponseEntity<SnapshotView> getByQuestion(
      @AuthenticationPrincipal Jwt jwt, @PathVariable long questionId) {
    return ResponseEntity.ok(lcsService.getSnapshotByQuestion(uid(jwt), questionId));
  }

  @GetMapping("/preferences")
  public ResponseEntity<PreferencesView> getPreferences(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(lcsService.getPreferences(uid(jwt)));
  }

  @PutMapping("/preferences")
  public ResponseEntity<PreferencesView> putPreferences(
      @AuthenticationPrincipal Jwt jwt, @RequestBody PreferencesView req) {
    return ResponseEntity.ok(lcsService.putPreferences(uid(jwt), req));
  }

  static long uid(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }
}
