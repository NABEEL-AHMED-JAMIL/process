package process.forms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A form as an Analytics dataset (MIG-279). Each submission is one JSON file under datasets/forms/form-N/ in the
 * workspace's bucket (its inbox's connection); the dataset is that folder's *.json, which Analytics reads with
 * union_by_name -- so a field added later is a new column, and older rows keep theirs (never dropped). A row is the
 * submission's own columns, then one column per answer: tables as JSON text, files by name, a signature as "Signed".
 * A status change rewrites the row in place.
 */
public final class FormDatasets {

    private FormDatasets() {
    }

    /** Registers a dataset with Analytics as the signed-in person; its id. */
    public interface Registry {
        long register(String connectionAlias, String path, String name);
    }

    public static String folderOf(long formId) {
        return "datasets/forms/form-" + formId + "/";
    }

    public static String globOf(long formId) {
        return folderOf(formId) + "*.json";
    }

    public static String keyOf(long formId, long submissionId) {
        return folderOf(formId) + "submission-" + submissionId + ".json";
    }

    public static String nameOf(FormStore.Form form) {
        String name = "Form: " + form.name;
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    /** The submission as one flat row. */
    public static Map<String, Object> rowOf(List<FormField> fields, FormStore.Submission s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("submission_id", s.submissionId);
        row.put("form_id", s.formId);
        row.put("form_version", s.formVersion);
        row.put("submitted_by", s.submittedByName);
        row.put("submitted_at", s.submittedAt == null ? null : s.submittedAt.toString());
        row.put("run_status", s.status);
        row.put("run_id", s.jobQueueId);
        row.put("approval_status", s.workflowStatus);
        row.put("approval_request_id", s.workflowInstanceId);
        row.put("approval_stage", s.workflowStage);
        Map<String, String> types = fields.stream().collect(Collectors.toMap(FormField::getKey, FormField::getType, (a, b) -> a));
        for (Map.Entry<String, Object> answer : s.answers.entrySet()) {
            if (FormFields.RESERVED.contains(answer.getKey()) || row.containsKey(answer.getKey())) {
                continue;
            }
            row.put(answer.getKey(), flat(types.get(answer.getKey()), answer.getValue()));
        }
        return row;
    }

    static Object flat(String type, Object value) {
        if (value instanceof Map && ((Map<?, ?>) value).containsKey("uploadId")) {
            return FormFields.SIGNATURE.equals(type) ? "Signed" : ((Map<?, ?>) value).get("name");
        }
        if (value instanceof List) {
            return FormSubmissionService.csvAnswer(value);
        }
        return value;
    }
}
