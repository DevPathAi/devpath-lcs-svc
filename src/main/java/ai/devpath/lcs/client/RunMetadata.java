package ai.devpath.lcs.client;

/** Metadata-only sandbox response from GET /internal/sandbox/sessions/recent/metadata. */
public record RunMetadata(String language, String status) {
}
