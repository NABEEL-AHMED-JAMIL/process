package process.forms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-277: a file or a signature is uploaded to the workspace's inbox before the form is sent -- checked against its
 * field -- and a submission may name only its own uploader's uploads to that field, each once. A lookup offers another
 * form's answers and takes only one of them. With the store in memory and the bucket as a recorder.
 */
class FormUploadServiceTest {

    static final long A = 2924L;
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final FormSubmissionServiceTest.RecordingBucket bucket = new FormSubmissionServiceTest.RecordingBucket();
    private FormInbox.Location inbox = FormInbox.Location.at("wound-inbox");
    private FormService forms;
    private FormSubmissionService service;

    @BeforeEach
    void wire() {
        this.forms = new FormService(this.store, mock(TransactionServiceImpl.class));
        this.service = new FormSubmissionService(this.store, this.forms, () -> this.inbox, this.bucket, mock(ProducerBulkEngine.class),
            mock(TransactionServiceImpl.class), mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private static void as(long user) {
        TenantContext.set(A, "TENANT_USER", user, "user" + user + "@clinic.example");
    }

    private long form() {
        FormField photo = new FormField("photo", "Wound photo", "file", true, null, null);
        photo.setAccept(Arrays.asList("jpg", "png"));
        photo.setMaxSizeMb(1);
        FormField signed = new FormField("signed", "Signature", "signature", false, null, null);
        return this.store.create(A, "Visit", null, FormStore.ACTIVE, FormFields.valid(Arrays.asList(photo, signed)), null, 1L);
    }

    @SuppressWarnings("unchecked")
    private static long uploadId(ResponseDto answer) {
        return ((Number) ((Map<String, Object>) answer.getData()).get("uploadId")).longValue();
    }

    @Test
    void aFileIsCheckedWrittenToTheInboxAndSentOnceByItsUploader() {
        long formId = form();
        as(4597L);

        ResponseDto uploaded = this.service.upload(formId, "photo", "C:\\photos\\left heel.JPG", "jpeg bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(uploaded.getStatus()).isEqualTo(SUCCESS);
        assertThat(this.bucket.uploads).hasSize(1);
        assertThat(this.bucket.uploads.get(0)).startsWith(A + " wound-inbox intake/forms/form-" + formId + "/uploads/")
            .endsWith("-left-heel.JPG image/jpeg");
        long id = uploadId(uploaded);

        as(4599L);
        ResponseDto notTheirs = this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("photo", id)));
        assertThat(notTheirs.getStatus()).as("another person cannot send it").isEqualTo(ERROR);

        as(4597L);
        ResponseDto sent = this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("photo", id)));
        assertThat(sent.getStatus()).isEqualTo(SUCCESS);
        Object kept = this.store.submissions.values().iterator().next().answers.get("photo");
        assertThat(kept.toString()).contains("left heel.JPG").contains("uploadId=" + id);

        ResponseDto again = this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("photo", id)));
        assertThat(again.getStatus()).as("each file once").isEqualTo(ERROR);
    }

    @Test
    void theFieldsLimitsAndTheInboxAreChecked() {
        long formId = form();
        as(4597L);
        assertThat(this.service.upload(formId, "photo", "notes.pdf", new byte[10]).getMessage()).contains("takes jpg, png files");
        assertThat(this.service.upload(formId, "photo", "big.png", new byte[2 * 1024 * 1024]).getMessage()).contains("at most 1 MB");
        assertThat(this.service.upload(formId, "photo", "empty.png", new byte[0]).getMessage()).contains("empty");
        assertThat(this.service.upload(formId, "nothing", "a.png", PNG).getMessage()).contains("no file or signature field");
        assertThat(this.service.upload(formId, "signed", "sig.png", "not a png".getBytes(StandardCharsets.UTF_8)).getMessage())
            .contains("drawn PNG");
        assertThat(this.service.upload(formId, "signed", "sig.png", PNG).getStatus()).isEqualTo(SUCCESS);
        this.inbox = FormInbox.Location.none("no inbox is set up");
        assertThat(this.service.upload(formId, "photo", "a.png", PNG).getMessage()).contains("no inbox is set up");
        assertThat(this.bucket.uploads).hasSize(1);
    }

    @Test
    void theUploadedNameLosesItsPathAndOddCharacters() {
        assertThat(FormSubmissionService.safeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FormSubmissionService.safeName("a b<script>.pdf")).isEqualTo("a-b_script_.pdf");
        assertThat(FormSubmissionService.safeName("..")).isEqualTo("file");
        assertThat(FormSubmissionService.contentTypeOf("exe")).isEqualTo("application/octet-stream");
    }

    @Test
    void aLookupOffersAnotherFormsAnswersAndTakesOnlyOne() {
        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        long patients = this.store.create(A, "Patients", null, FormStore.ACTIVE,
            FormFields.valid(Collections.singletonList(new FormField("patient_id", "Patient ID", "text", true, null, null))), null, 1L);
        this.store.receive(A, patients, 1, Collections.singletonMap("patient_id", "P-100"), null, 4537L, "admin");
        this.store.receive(A, patients, 1, Collections.singletonMap("patient_id", "P-200"), null, 4537L, "admin");

        FormField patient = new FormField("patient", "Patient", "lookup", true, null, null);
        patient.setLookup(new FormField.Lookup(patients, "missing"));
        FormSaveRequest save = new FormSaveRequest();
        save.setName("Visit");
        save.setStatus(FormStore.ACTIVE);
        save.setFields(Collections.singletonList(patient));
        assertThat(this.forms.save(save).getMessage()).contains("has no field 'missing'");

        patient.setLookup(new FormField.Lookup(patients, "patient_id"));
        ResponseDto saved = this.forms.save(save);
        assertThat(saved.getStatus()).isEqualTo(SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) saved.getData();
        assertThat(view.get("lookupValues").toString()).contains("P-100").contains("P-200");
        long visit = ((Number) view.get("formId")).longValue();

        as(4597L);
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("patient", "P-300");
        assertThat(this.service.submit(new FormSubmitRequest(visit, answers)).getMessage()).contains("choose one of the listed values");
        answers.put("patient", "p-100");
        assertThat(this.service.submit(new FormSubmitRequest(visit, answers)).getStatus()).isEqualTo(SUCCESS);
        List<FormStore.Submission> kept = this.store.submissions(A, visit, 5);
        assertThat(kept.get(0).answers).containsEntry("patient", "P-100");
    }
}
