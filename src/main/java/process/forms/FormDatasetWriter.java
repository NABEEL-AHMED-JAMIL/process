package process.forms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.pipeline.backing.BucketStore;

import java.util.List;

/**
 * Writes a form's dataset rows (MIG-279, FormDatasets): one JSON file per submission in the bucket the form's rows are
 * kept in. Best effort: a row that cannot be written is logged; the submission stands.
 */
@Component
public class FormDatasetWriter {

    /** The most submissions written when a form first becomes a dataset: its newest. */
    static final int BACKFILL = 1000;

    private static final Logger logger = LoggerFactory.getLogger(FormDatasetWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FormStore store;
    private final BucketStore buckets;

    public FormDatasetWriter(FormStore store, BucketStore buckets) {
        this.store = store;
        this.buckets = buckets;
    }

    /** The submission's row, in {@code bucket}; false when it was not written. */
    public boolean write(long tenantId, FormStore.Form form, FormStore.Submission submission, String bucket) {
        if (bucket == null) {
            return false;
        }
        try {
            if (this.buckets.unavailable().isPresent()) {
                return false;
            }
            List<FormField> fields = this.store.fieldsAt(tenantId, form.formId, submission.formVersion).orElse(form.fields);
            this.buckets.upload(tenantId, bucket, FormDatasets.keyOf(form.formId, submission.submissionId),
                JSON.writeValueAsBytes(FormDatasets.rowOf(fields, submission)), "application/json");
            return true;
        } catch (Exception unwritten) {
            logger.warn("Submission {}'s dataset row was not written to {}: {}", submission.submissionId, bucket, unwritten.getMessage());
            return false;
        }
    }

    /** Every submission the form has (its newest BACKFILL), when it first becomes a dataset; how many rows were written. */
    public int backfill(long tenantId, FormStore.Form form, String bucket) {
        int written = 0;
        for (FormStore.Submission submission : this.store.submissions(tenantId, form.formId, BACKFILL)) {
            if (this.write(tenantId, form, submission, bucket)) {
                written++;
            }
        }
        return written;
    }
}
