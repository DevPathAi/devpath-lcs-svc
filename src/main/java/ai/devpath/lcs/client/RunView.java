package ai.devpath.lcs.client;

/**
 * sandbox-svc 최근 실행 세션 응답(GET /internal/sandbox/sessions/recent).
 * 업스트림(SandboxSessionView) 전체 형태를 미러 — LCS는 language/status 만 사용.
 */
public record RunView(
    Long id, Long userId, String language, Long contentId,
    String submittedCode, String stdout, String stderr, Integer exitCode, String status) {
}
