package process.forms;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.BucketStore;
import process.security.TenantContext;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-271: a file field set to send its files to Document Intelligence does so when a submission is kept -- each file read
 * from the inbox where its upload put it, as the workspace, and handed to media-service's intake with the form, the
 * submission and the field as its source. A field not set to, a form saved before the setting, and a signature send nothing.
 */
class FormDocumentsTest {

    static final long A = 2924L;
    static final byte[] PDF = "%PDF-1.4 wound report".getBytes(StandardCharsets.US_ASCII);

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final Bucket bucket = new Bucket();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private HttpServer media;
    private FormSubmissionService service;

    /** The inbox: what an upload wrote, read back by the same key. */
    static final class Bucket implements BucketStore {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> reads = new ArrayList<>();

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            this.reads.add(tenantId + " " + bucket + " " + key);
            return this.objects.get(key);
        }

        @Override
        public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.objects.put(key, content);
        }
    }

    @BeforeEach
    void wire() throws Exception {
        this.media = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.media.createContext("/api/v1/internal/media/intake/files", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    body.write(buffer, 0, n);
                }
            }
            this.received.add(exchange.getRequestHeaders().getFirst("X-Internal-Token") + "\n" + body.toString("ISO-8859-1"));
            byte[] answer = "{\"status\":\"SUCCESS\",\"message\":\"1 document made, 0 duplicates, 0 refused.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        this.media.start();
        FormService forms = new FormService(this.store, mock(TransactionServiceImpl.class));
        this.service = new FormSubmissionService(this.store, forms, () -> FormInbox.Location.at("wound-inbox"), this.bucket,
            mock(ProducerBulkEngine.class), mock(TransactionServiceImpl.class), mock(PlatformTransactionManager.class));
        this.service.useDocuments(new FormDocuments(this.bucket, "http://127.0.0.1:" + this.media.getAddress().getPort(), "svc-token"));
    }

    @AfterEach
    void stop() {
        TenantContext.clear();
        this.media.stop(0);
    }

    private long form(boolean toDocuments) {
        FormField report = new FormField("report", "Wound report", "file", true, null, null);
        report.setAccept(Arrays.asList("pdf"));
        report.setToDocuments(toDocuments ? Boolean.TRUE : Boolean.FALSE);
        FormField photo = new FormField("photo", "Photo", "file", false, null, null);
        photo.setAccept(Arrays.asList("pdf"));
        return this.store.create(A, "Wound visit", null, FormStore.ACTIVE, FormFields.valid(Arrays.asList(report, photo)), null, 1L);
    }

    @SuppressWarnings("unchecked")
    private long upload(long formId, String field, String name) {
        ResponseDto uploaded = this.service.upload(formId, field, name, PDF);
        return ((Number) ((Map<String, Object>) uploaded.getData()).get("uploadId")).longValue();
    }

    private void waitFor(int requests) throws InterruptedException {
        long until = System.currentTimeMillis() + 10000;
        while (this.received.size() < requests && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
    }

    @Test
    void theSettingIsKeptOnlyWhenOnSoAFormSavedBeforeItReadsAsItDid() {
        FormField on = new FormField("a", "A", "file", false, null, null);
        on.setToDocuments(Boolean.TRUE);
        FormField off = new FormField("b", "B", "file", false, null, null);
        off.setToDocuments(Boolean.FALSE);
        FormField text = new FormField("c", "C", "text", false, null, null);
        text.setToDocuments(Boolean.TRUE);
        List<FormField> valid = FormFields.valid(Arrays.asList(on, off, text));
        assertThat(valid.get(0).getToDocuments()).isTrue();
        assertThat(valid.get(1).getToDocuments()).isNull();
        assertThat(valid.get(2).getToDocuments()).as("only a file field sends files").isNull();
    }

    @Test
    void aKeptSubmissionsFilesOfAFieldSetToGoAreHandedToDocumentIntelligenceWithTheirSource() throws Exception {
        long formId = form(true);
        TenantContext.set(A, "TENANT_USER", 4597L, "alex@clinic.example");
        long report = upload(formId, "report", "left heel.pdf");
        long photo = upload(formId, "photo", "photo.pdf");
        Map<String, Object> answers = new HashMap<>();
        answers.put("report", report);
        answers.put("photo", photo);
        assertThat(this.service.submit(new FormSubmitRequest(formId, answers)).getStatus()).isEqualTo("SUCCESS");
        waitFor(1);
        Thread.sleep(200);
        assertThat(this.received).hasSize(1);
        String sent = this.received.get(0);
        long submission = this.store.submissions.keySet().iterator().next();
        assertThat(sent).startsWith("svc-token\n")
            .contains("name=\"tenantId\"\r\nContent-Length: 4\r\n\r\n2924")
            .contains("name=\"channel\"\r\nContent-Length: 4\r\n\r\nform")
            .contains("form:" + formId + "/submission:" + submission + "/field:report")
            .contains("Form 'Wound visit', submission #" + submission + " (Wound report)")
            .contains("name=\"actor\"\r\nContent-Length: 4\r\n\r\n4597")
            .contains("filename=\"left heel.pdf\"")
            .contains("%PDF-1.4 wound report")
            .doesNotContain("photo.pdf");
        assertThat(this.bucket.reads).hasSize(1).allSatisfy(read -> assertThat(read).startsWith(A + " wound-inbox intake/forms/form-" + formId));
    }

    @Test
    void aFieldNotSetToGoSendsNothing() throws Exception {
        long formId = form(false);
        TenantContext.set(A, "TENANT_USER", 4597L, "alex@clinic.example");
        long report = upload(formId, "report", "left heel.pdf");
        assertThat(this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("report", report))).getStatus()).isEqualTo("SUCCESS");
        Thread.sleep(300);
        assertThat(this.received).isEmpty();
        assertThat(this.bucket.reads).isEmpty();
    }
}
