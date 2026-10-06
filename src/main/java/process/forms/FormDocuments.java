package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;
import process.pipeline.backing.BucketStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A form's files to Document Intelligence (MIG-271): when a submission is kept, every file of a file field the form's
 * builder set to "send to Document Intelligence" is read from the workspace inbox where the upload put it (the bucket
 * contract, as the run's workspace) and handed to media-service's intake -- POST /internal/media/intake/files, channel form,
 * the service token -- which makes one document per file (a duplicate of an earlier one by checksum is not a second) and
 * extracts it. The document says where it came from: the form, the submission and the field.
 *
 * In the background, after the submission is answered: a person sending a form never waits on OCR, and Document
 * Intelligence being down never fails a submission -- it is logged, and the files stay in the inbox as always.
 */
@Component
public class FormDocuments {

    private static final Logger logger = LoggerFactory.getLogger(FormDocuments.class);
    private static final MediaType BYTES = MediaType.get("application/octet-stream");
    /** A form's file field takes at most 25 MB (FormFields.MAX_SIZE_MB); this is only a guard on the read. */
    static final long MAX_BYTES = 26L * 1024 * 1024;

    private final BucketStore buckets;
    private final String url;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build();
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), work -> {
        Thread thread = new Thread(work, "form-documents");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public FormDocuments(BucketStore buckets, @Value("${media.url:http://media:9110}") String mediaUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.buckets = buckets;
        this.url = mediaUrl.replaceAll("/+$", "") + "/api/v1/internal/media/intake/files";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    /** One field's files, as the submission's answer keeps them. */
    static final class Batch {
        final FormField field;
        final List<Map<String, Object>> files;

        Batch(FormField field, List<Map<String, Object>> files) {
            this.field = field;
            this.files = files;
        }
    }

    /** The files of the form's "to Document Intelligence" fields in a kept submission, a batch per field. */
    @SuppressWarnings("unchecked")
    static List<Batch> batches(FormStore.Form form, FormStore.Submission submission) {
        List<Batch> batches = new ArrayList<>();
        for (FormField field : form.fields) {
            if (!FormFields.FILE.equals(field.getType()) || !Boolean.TRUE.equals(field.getToDocuments())) {
                continue;
            }
            Object answer = submission.answers.get(field.getKey());
            List<Map<String, Object>> files = new ArrayList<>();
            if (answer instanceof List) {
                for (Object file : (List<Object>) answer) {
                    if (file instanceof Map && ((Map<String, Object>) file).get("key") != null) {
                        files.add((Map<String, Object>) file);
                    }
                }
            }
            if (!files.isEmpty()) {
                batches.add(new Batch(field, files));
            }
        }
        return batches;
    }

    /** Hands the submission's files to Document Intelligence in the background; nothing to send is nothing done. */
    public void send(long tenantId, FormStore.Form form, FormStore.Submission submission, Long person) {
        List<Batch> batches = batches(form, submission);
        if (batches.isEmpty()) {
            return;
        }
        try {
            this.senders.execute(() -> {
                for (Batch batch : batches) {
                    this.deliver(tenantId, form, submission, person, batch);
                }
            });
        } catch (RejectedExecutionException full) {
            logger.warn("Form {} submission {}: {} file field(s) were not sent to Document Intelligence: too many waiting", form.formId,
                submission.submissionId, batches.size());
        }
    }

    void deliver(long tenantId, FormStore.Form form, FormStore.Submission submission, Long person, Batch batch) {
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("tenantId", String.valueOf(tenantId))
            .addFormDataPart("channel", "form")
            .addFormDataPart("ref", String.format("form:%d/submission:%d/field:%s", form.formId, submission.submissionId, batch.field.getKey()))
            .addFormDataPart("label", String.format("Form '%s', submission #%d (%s)", form.name, submission.submissionId, batch.field.getLabel()));
        if (person != null) {
            body.addFormDataPart("actor", String.valueOf(person));
        }
        int read = 0;
        for (Map<String, Object> file : batch.files) {
            String bucket = String.valueOf(file.get("bucket"));
            String key = String.valueOf(file.get("key"));
            String name = file.get("name") == null ? key.substring(key.lastIndexOf('/') + 1) : String.valueOf(file.get("name"));
            try {
                byte[] bytes = this.buckets.read(tenantId, bucket, key, MAX_BYTES);
                body.addFormDataPart("files", name, RequestBody.create(bytes, BYTES));
                read++;
            } catch (Exception unread) {
                logger.warn("Form {} submission {}: a file of field {} could not be read for Document Intelligence: {}", form.formId,
                    submission.submissionId, batch.field.getKey(), unread.getMessage());
            }
        }
        if (read == 0) {
            return;
        }
        Request request = new Request.Builder().url(this.url).header("X-Internal-Token", this.serviceToken).post(body.build()).build();
        try (Response response = this.http.newCall(request).execute()) {
            ResponseBody answer = response.body();
            JsonNode envelope = answer == null ? null : this.json.readTree(answer.string());
            String message = envelope != null && envelope.hasNonNull("message") ? envelope.get("message").asText() : "";
            if (response.isSuccessful()) {
                logger.info("Form {} submission {}: field {} sent {} file(s) to Document Intelligence: {}", form.formId, submission.submissionId,
                    batch.field.getKey(), read, message);
            } else {
                logger.warn("Form {} submission {}: Document Intelligence refused field {}'s files ({}): {}", form.formId,
                    submission.submissionId, batch.field.getKey(), response.code(), message);
            }
        } catch (Exception unreachable) {
            logger.warn("Form {} submission {}: Document Intelligence could not be reached for field {}: {}", form.formId,
                submission.submissionId, batch.field.getKey(), unreachable.getMessage());
        }
    }
}
