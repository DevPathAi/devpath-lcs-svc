package ai.devpath.lcs.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Purpose-aware field allowlist and strict validation for one-request context. */
final class SnapshotPolicy {

  static final String QUESTION_ATTACHMENT = "question_attachment";
  static final String MENTOR_PROMPT = "mentor_prompt";
  static final String PRIVATE = "private";

  static final String CURRENT_CONTENT = "current_content";
  static final String RECENT_ACTIVITY = "recent_activity";
  static final String ACTIVE_TAGS = "active_tags";
  static final String TAG_REPUTATION = "tag_reputation";
  static final String CURRENT_PATH = "current_path";
  static final String RECENT_ERRORS = "recent_errors";
  static final String CURRENT_CODE = "current_code";
  static final String RECENT_OUTPUT = "recent_output";
  static final String REVIEW_SUMMARY = "review_summary";

  private static final int MAX_CODE_BYTES = 64 * 1024;
  private static final int MAX_OUTPUT_BYTES = 64 * 1024;
  private static final int MAX_ERROR_BYTES = 4 * 1024;
  private static final int MAX_REVIEW_TEXT_BYTES = 2 * 1024;
  private static final int MAX_LIST_ITEMS = 20;

  private static final List<String> LEGACY_DEFAULT_FIELDS = List.of(
      CURRENT_CONTENT, RECENT_ACTIVITY, ACTIVE_TAGS, TAG_REPUTATION, CURRENT_PATH, RECENT_ERRORS);
  private static final Set<String> COMMUNITY_FIELDS = Set.copyOf(LEGACY_DEFAULT_FIELDS);
  private static final Set<String> MENTOR_FIELDS = Set.of(
      CURRENT_CONTENT, RECENT_ACTIVITY, ACTIVE_TAGS, TAG_REPUTATION, CURRENT_PATH, RECENT_ERRORS,
      CURRENT_CODE, RECENT_OUTPUT, REVIEW_SUMMARY);
  private static final Set<String> REQUEST_CONTEXT_FIELDS = Set.of(
      CURRENT_CODE, RECENT_ERRORS, RECENT_OUTPUT, REVIEW_SUMMARY);
  private static final Set<String> COMMITTED_MENTOR_FIELDS = Set.of(
      CURRENT_CONTENT, RECENT_ACTIVITY, RECENT_ERRORS, CURRENT_CODE, RECENT_OUTPUT,
      REVIEW_SUMMARY);

  private SnapshotPolicy() {
  }

  static String normalizePurpose(String purpose) {
    if (!QUESTION_ATTACHMENT.equals(purpose) && !MENTOR_PROMPT.equals(purpose)) {
      throw new IllegalArgumentException("unsupported snapshot purpose");
    }
    return purpose;
  }

  static List<String> normalizeRequestedFields(String purpose, List<String> requestedFields) {
    String normalizedPurpose = normalizePurpose(purpose);
    if (requestedFields == null || requestedFields.isEmpty()) {
      return MENTOR_PROMPT.equals(normalizedPurpose)
          ? List.of(CURRENT_CONTENT)
          : LEGACY_DEFAULT_FIELDS;
    }
    Set<String> allowed = QUESTION_ATTACHMENT.equals(normalizedPurpose)
        ? COMMUNITY_FIELDS
        : MENTOR_FIELDS;
    if (requestedFields.size() > allowed.size()) {
      throw new IllegalArgumentException("too many requested context fields");
    }
    List<String> normalized = new ArrayList<>(requestedFields.size());
    Set<String> unique = new HashSet<>();
    for (String field : requestedFields) {
      if (field == null || !allowed.contains(field)) {
        throw new IllegalArgumentException("unknown requested context field");
      }
      if (!unique.add(field)) {
        throw new IllegalArgumentException("duplicate requested context field");
      }
      normalized.add(field);
    }
    return List.copyOf(normalized);
  }

  static Map<String, Object> validateRequestContext(
      String purpose, List<String> requestedFields, Map<String, Object> requestContext) {
    String normalizedPurpose = normalizePurpose(purpose);
    Map<String, Object> supplied = requestContext == null ? Map.of() : requestContext;
    if (QUESTION_ATTACHMENT.equals(normalizedPurpose) && !supplied.isEmpty()) {
      throw new IllegalArgumentException("question attachments do not accept request context");
    }
    Set<String> selected = Set.copyOf(requestedFields);
    LinkedHashMap<String, Object> validated = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : supplied.entrySet()) {
      String field = entry.getKey();
      if (!REQUEST_CONTEXT_FIELDS.contains(field) || !selected.contains(field)) {
        throw new IllegalArgumentException("request context does not match selected fields");
      }
      validated.put(field, validateField(field, entry.getValue()));
    }
    return Collections.unmodifiableMap(validated);
  }

  /** Revalidates the DB-authoritative payload before it crosses the Mentor trust boundary. */
  static void validateCommittedMentorContent(
      List<String> fieldsIncluded, Map<String, Object> content) {
    if (fieldsIncluded == null || content == null
        || fieldsIncluded.size() > COMMITTED_MENTOR_FIELDS.size()) {
      throw new IllegalArgumentException("invalid committed mentor context");
    }
    Set<String> unique = new HashSet<>(fieldsIncluded);
    if (unique.size() != fieldsIncluded.size()
        || !COMMITTED_MENTOR_FIELDS.containsAll(unique)
        || !content.keySet().equals(unique)) {
      throw new IllegalArgumentException("invalid committed mentor context");
    }
    for (String field : fieldsIncluded) {
      Object value = content.get(field);
      switch (field) {
        case CURRENT_CONTENT -> currentContent(value);
        case RECENT_ACTIVITY -> recentActivity(value);
        case CURRENT_CODE, RECENT_ERRORS, RECENT_OUTPUT, REVIEW_SUMMARY ->
            validateField(field, value);
        default -> throw new IllegalArgumentException("invalid committed mentor context");
      }
    }
  }

  /** Uses the committed Mentor boundary as the single field-shape authority during assembly. */
  static boolean isValidMentorSourceField(String field, Object value) {
    try {
      validateCommittedMentorContent(List.of(field), Map.of(field, value));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void currentContent(Object value) {
    Map<String, Object> map = stringKeyedMap(value, CURRENT_CONTENT);
    requireExactKeys(map, Set.of("contentId", "title", "track"), CURRENT_CONTENT);
    positiveLong(map.get("contentId"), "current_content.contentId");
    boundedString(map.get("title"), "current_content.title", 2 * 1024, false);
    boundedString(map.get("track"), "current_content.track", 128, false);
  }

  private static void recentActivity(Object value) {
    if (!(value instanceof List<?> runs) || runs.size() > 5) {
      throw new IllegalArgumentException("recent_activity must be a bounded array");
    }
    for (Object valueItem : runs) {
      Map<String, Object> item = stringKeyedMap(valueItem, "recent_activity item");
      requireExactKeys(item, Set.of("language", "status"), "recent_activity item");
      boundedString(item.get("language"), "recent_activity.language", 128, false);
      boundedString(item.get("status"), "recent_activity.status", 128, false);
    }
  }

  private static Object validateField(String field, Object value) {
    return switch (field) {
      case CURRENT_CODE -> boundedString(value, "current_code", MAX_CODE_BYTES, false);
      case RECENT_ERRORS -> stringList(value, "recent_errors", 10, MAX_ERROR_BYTES);
      case RECENT_OUTPUT -> output(value);
      case REVIEW_SUMMARY -> review(value);
      default -> throw new IllegalArgumentException("unsupported request context field");
    };
  }

  private static Map<String, Object> output(Object value) {
    Map<String, Object> map = stringKeyedMap(value, "recent_output");
    requireExactKeys(map, Set.of("stdout", "stderr", "truncated"), "recent_output");
    String stdout = boundedString(map.get("stdout"), "recent_output.stdout", MAX_OUTPUT_BYTES, true);
    String stderr = boundedString(map.get("stderr"), "recent_output.stderr", MAX_OUTPUT_BYTES, true);
    if (utf8Bytes(stdout) + utf8Bytes(stderr) > MAX_OUTPUT_BYTES) {
      throw new IllegalArgumentException("recent_output exceeds byte limit");
    }
    if (!(map.get("truncated") instanceof Boolean truncated)) {
      throw new IllegalArgumentException("recent_output.truncated must be boolean");
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("stdout", stdout);
    result.put("stderr", stderr);
    result.put("truncated", truncated);
    return Collections.unmodifiableMap(result);
  }

  private static Map<String, Object> review(Object value) {
    Map<String, Object> map = stringKeyedMap(value, "review_summary");
    requireExactKeys(map, Set.of("confidence", "strengths", "improvements", "security"),
        "review_summary");
    int confidence = exactInteger(map.get("confidence"), "review_summary.confidence");
    if (confidence < 0 || confidence > 100) {
      throw new IllegalArgumentException("review_summary.confidence is out of range");
    }
    List<String> strengths = stringList(
        map.get("strengths"), "review_summary.strengths", MAX_LIST_ITEMS, MAX_REVIEW_TEXT_BYTES);
    List<Map<String, Object>> improvements = issues(
        map.get("improvements"), "review_summary.improvements");
    List<Map<String, Object>> security = issues(
        map.get("security"), "review_summary.security");
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("confidence", confidence);
    result.put("strengths", strengths);
    result.put("improvements", improvements);
    result.put("security", security);
    return Collections.unmodifiableMap(result);
  }

  private static List<Map<String, Object>> issues(Object value, String name) {
    if (!(value instanceof List<?> list) || list.size() > MAX_LIST_ITEMS) {
      throw new IllegalArgumentException(name + " must be a bounded array");
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      Map<String, Object> issue = stringKeyedMap(item, name + " item");
      if (!issue.keySet().contains("message") || !issue.keySet().contains("severity")
          || !Set.of("message", "line", "severity").containsAll(issue.keySet())) {
        throw new IllegalArgumentException(name + " item has invalid keys");
      }
      String message = boundedString(
          issue.get("message"), name + ".message", MAX_REVIEW_TEXT_BYTES, false);
      String severity = boundedString(issue.get("severity"), name + ".severity", 16, false);
      if (!Set.of("info", "warning", "error").contains(severity)) {
        throw new IllegalArgumentException(name + ".severity is invalid");
      }
      LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
      normalized.put("message", message);
      if (issue.containsKey("line") && issue.get("line") != null) {
        int line = exactInteger(issue.get("line"), name + ".line");
        if (line <= 0) {
          throw new IllegalArgumentException(name + ".line must be positive");
        }
        normalized.put("line", line);
      }
      normalized.put("severity", severity);
      result.add(Collections.unmodifiableMap(normalized));
    }
    return List.copyOf(result);
  }

  private static List<String> stringList(
      Object value, String name, int maxItems, int maxItemBytes) {
    if (!(value instanceof List<?> list) || list.size() > maxItems) {
      throw new IllegalArgumentException(name + " must be a bounded string array");
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      result.add(boundedString(item, name + " item", maxItemBytes, false));
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> stringKeyedMap(Object value, String name) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(name + " keys must be strings");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static void requireExactKeys(
      Map<String, Object> map, Set<String> expected, String name) {
    if (!map.keySet().equals(expected)) {
      throw new IllegalArgumentException(name + " has invalid keys");
    }
  }

  private static String boundedString(Object value, String name, int maxBytes, boolean allowEmpty) {
    if (!(value instanceof String text) || (!allowEmpty && text.isBlank())) {
      throw new IllegalArgumentException(name + " must be a string");
    }
    if (utf8Bytes(text) > maxBytes) {
      throw new IllegalArgumentException(name + " exceeds byte limit");
    }
    return text;
  }

  private static int exactInteger(Object value, String name) {
    if (value instanceof Integer integer) {
      return integer;
    }
    if (value instanceof Long number
        && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
      return number.intValue();
    }
    throw new IllegalArgumentException(name + " must be an integer");
  }

  private static long positiveLong(Object value, String name) {
    long result;
    if (value instanceof Integer number) {
      result = number.longValue();
    } else if (value instanceof Long number) {
      result = number;
    } else {
      throw new IllegalArgumentException(name + " must be an integer");
    }
    if (result <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return result;
  }

  private static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
