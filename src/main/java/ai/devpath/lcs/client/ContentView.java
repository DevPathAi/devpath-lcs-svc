package ai.devpath.lcs.client;

/**
 * learning-svc 내부 콘텐츠 조회 응답(GET /internal/contents/{id}).
 * 업스트림(InternalContentView) 전체 형태를 미러 — LCS는 id/title/track 만 사용(body 불필요).
 */
public record ContentView(long id, String slug, String title, String track, String body) {
}
