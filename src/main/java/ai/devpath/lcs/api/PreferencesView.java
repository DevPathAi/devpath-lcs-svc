package ai.devpath.lcs.api;

/** 프라이버시 6토글 + 기본 노출범위. */
public record PreferencesView(
    boolean collectCurrentContent,
    boolean collectLearningPath,
    boolean collectActiveTags,
    boolean collectRecentErrors,
    boolean collectTagReputation,
    boolean collectLevel,
    String defaultVisibility) {
}
