package ai.devpath.lcs.api;

import java.util.List;
import java.util.Map;

/** Owner- and purpose-authorized Mentor consumption envelope. */
public record MentorSnapshotView(
    Long snapshotId,
    String purpose,
    String visibility,
    List<String> fieldsIncluded,
    Map<String, Object> content) {
}
