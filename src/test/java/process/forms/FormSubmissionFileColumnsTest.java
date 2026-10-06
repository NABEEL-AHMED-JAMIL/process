package process.forms;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-325: a photo taken on a phone reaches a pipeline step by name. A file answer is a list of uploads, which no step
 * can read as a column, so the submission's file also carries the first upload's key and bucket beside it.
 */
class FormSubmissionFileColumnsTest {

    private static Map<String, Object> upload(String key) {
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("key", key);
        upload.put("name", "photo.jpg");
        upload.put("bucket", "inbox-bucket");
        upload.put("uploadId", 7);
        return upload;
    }

    private static final List<FormField> FIELDS = Arrays.asList(
        new FormField("image", "Photo", FormFields.FILE, true, null, null),
        new FormField("signed", "Signature", FormFields.SIGNATURE, false, null, null),
        new FormField("patient_id", "Patient", "text", true, null, null));

    @Test
    void aFileAnswersFirstUploadBecomesKeyAndBucketColumns() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("image", Arrays.asList(upload("intake/forms/form-1/uploads/a.jpg"), upload("intake/forms/form-1/uploads/b.jpg")));
        answers.put("signed", upload("intake/forms/form-1/uploads/sig.png"));
        answers.put("patient_id", "SYN-001");

        Map<String, Object> flat = FormSubmissionService.flatFileColumns(FIELDS, answers, answers.keySet());

        assertThat(flat).containsEntry("image_key", "intake/forms/form-1/uploads/a.jpg")
            .containsEntry("image_bucket", "inbox-bucket")
            .containsEntry("signed_key", "intake/forms/form-1/uploads/sig.png")
            .doesNotContainKey("patient_id_key");
    }

    @Test
    void aColumnTheFormAlreadyHasIsNotOverwritten() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("image", Collections.singletonList(upload("intake/new.jpg")));
        answers.put("image_key", "typed by hand");

        Map<String, Object> flat = FormSubmissionService.flatFileColumns(FIELDS, answers, new HashSet<>(answers.keySet()));

        assertThat(flat).doesNotContainKey("image_key").containsEntry("image_bucket", "inbox-bucket");
    }

    @Test
    void anEmptyOrMissingFileAnswerAddsNothing() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("image", Collections.emptyList());

        assertThat(FormSubmissionService.flatFileColumns(FIELDS, answers, answers.keySet())).isEmpty();
    }
}
