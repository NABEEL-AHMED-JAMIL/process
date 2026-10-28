package process.forms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ZT-I34 (owner 2026-10-27): what each key of a form submission's request subject is (SubjectFields), for workflow-service,
 * service to service with the shared X-Internal-Token. A request started before its form said which answers are personal
 * carries no fields; workflow-service asks here once and keeps the answer, so those requests are masked as new ones are.
 * The fields are those of the version the submission answered, each personal when that version or the form now says so.
 * The gateway never exposes /internal.
 */
@RestController
@RequestMapping("/internal/forms")
public class InternalFormSubjectRestApi {

    /** The role the read is made with: the workspace itself. */
    static final String WORKSPACE_ROLE = "TENANT_ADMIN";

    private final Logger logger = LoggerFactory.getLogger(InternalFormSubjectRestApi.class);
    private final FormStore store;
    private final byte[] token;

    public InternalFormSubjectRestApi(FormStore store, @Value("${internal.service-token:}") String token) {
        this.store = store;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Which submission's subject is asked about. */
    public static class FieldsRequest {
        private Long tenantId;
        private Long submissionId;

        public Long getTenantId() { return tenantId; }

        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

        public Long getSubmissionId() { return submissionId; }

        public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    }

    /** {fields: [...]} for the submission; an ERROR saying so when the workspace has no such submission. */
    @PostMapping(value = "/subjectFields", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subjectFields(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody FieldsRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getSubmissionId() == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId) and the submission (submissionId)."));
        }
        long tenantId = request.getTenantId();
        TenantContext.set(tenantId, WORKSPACE_ROLE, null, "workflow-service");
        try {
            Optional<FormStore.Submission> submission = this.store.submission(tenantId, request.getSubmissionId());
            Optional<FormStore.Form> form = submission.flatMap(s -> this.store.find(tenantId, s.formId));
            if (!submission.isPresent() || !form.isPresent()) {
                return ResponseEntity.ok(new ResponseDto("ERROR", "No submission " + request.getSubmissionId() + " in this workspace."));
            }
            List<FormField> current = form.get().fields;
            List<FormField> answered = this.store.fieldsAt(tenantId, form.get().formId, submission.get().formVersion).orElse(current);
            return ResponseEntity.ok(new ResponseDto("SUCCESS", "Subject fields.", Map.of("fields", SubjectFields.of(answered, current))));
        } finally {
            TenantContext.clear();
        }
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a subject fields request without the internal token.");
        }
        return ok;
    }
}
