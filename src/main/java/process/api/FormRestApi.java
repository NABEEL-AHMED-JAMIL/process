package process.api;

import java.util.Map;
import process.forms.FormSharing;
import org.springframework.beans.factory.annotation.Autowired;
import org.barco.platform.security.BuilderAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import process.forms.FormFields;
import process.forms.FormSaveRequest;
import process.forms.FormService;
import process.forms.FormStatusRequest;
import process.forms.FormSubmissionService;
import process.forms.FormSubmitRequest;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import java.io.IOException;

/**
 * Wave 5 Forms (lite): a workspace's forms, built by its administrators and filled in by its members -- inside the
 * workspace only (owner decision 2026-09-30: no public or expiring links yet). /form.json is new: the gateway's fallback
 * sends it to Core, and PageAccessInterceptor gates it as the page 'forms' (PageKey.FORMS) for a tenant user.
 *
 * <ul>
 *   <li>GET form.json/list?withArchived= -- the workspace's forms (a member: the Active ones);</li>
 *   <li>GET form.json/fetch?formId= -- one form with its fields;</li>
 *   <li>GET form.json/linkableJobs -- the jobs a form may start (an administrator's);</li>
 *   <li>POST form.json/save -- create or replace a form (an administrator's; a builder action);</li>
 *   <li>POST form.json/status {formId, status} -- Draft, Active or Archived (an administrator's; a builder action);</li>
 *   <li>POST form.json/upload (multipart: formId, field, file) -- a file or drawn signature for a form's field, before it
 *       is sent (MIG-277); the answer names it by uploadId. Data entry, as submit.</li>
 *   <li>POST form.json/submit {formId, answers} -- fill it in; when the form names a job, the submission starts it
 *       (FormSubmissionService). Every member's, in either management mode: it is data entry, not building.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class FormRestApi {

    private final FormService forms;
    private final FormSubmissionService submissions;

    private FormSharing sharing;

    public FormRestApi(FormService forms, FormSubmissionService submissions) {
        this.forms = forms;
        this.submissions = submissions;
    }

    /** MIG-278: sharing by link (absent in the tests that build this controller by hand). */
    @Autowired(required = false)
    public void setSharing(FormSharing sharing) {
        this.sharing = sharing;
    }

    /** MIG-278: whether this workspace lets its forms be shared by link (off until a workspace admin turns it on). */
    @RequestMapping(value = "/form.json/sharePolicy", method = RequestMethod.GET)
    public ResponseEntity<?> sharePolicy() {
        return new ResponseEntity<>(this.sharing.policy(), HttpStatus.OK);
    }

    @BuilderAction
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/sharePolicy", method = RequestMethod.POST)
    public ResponseEntity<?> setSharePolicy(@RequestBody Map<String, Boolean> body) {
        return new ResponseEntity<>(this.sharing.setPolicy(body == null ? null : body.get("enabled")), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/shareLinks", method = RequestMethod.GET)
    public ResponseEntity<?> shareLinks(@RequestParam Long formId) {
        return new ResponseEntity<>(this.sharing.list(formId), HttpStatus.OK);
    }

    @BuilderAction
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/shareLinks/create", method = RequestMethod.POST)
    public ResponseEntity<?> createShareLink(@RequestBody FormSharing.CreateRequest request) {
        return new ResponseEntity<>(this.sharing.create(request), HttpStatus.OK);
    }

    @BuilderAction
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/shareLinks/revoke", method = RequestMethod.POST)
    public ResponseEntity<?> revokeShareLink(@RequestParam Long linkId) {
        return new ResponseEntity<>(this.sharing.revoke(linkId), HttpStatus.OK);
    }

    @RequestMapping(value = "/form.json/list", method = RequestMethod.GET)
    public ResponseEntity<?> list(@RequestParam(required = false, defaultValue = "false") boolean withArchived) {
        return new ResponseEntity<>(this.forms.list(withArchived), HttpStatus.OK);
    }

    @RequestMapping(value = "/form.json/fetch", method = RequestMethod.GET)
    public ResponseEntity<?> fetch(@RequestParam Long formId) {
        return new ResponseEntity<>(this.forms.fetch(formId), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/linkableJobs", method = RequestMethod.GET)
    public ResponseEntity<?> linkableJobs() {
        return new ResponseEntity<>(this.forms.linkableJobs(), HttpStatus.OK);
    }

    @BuilderAction
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody FormSaveRequest request) {
        return new ResponseEntity<>(this.forms.save(request), HttpStatus.OK);
    }

    @BuilderAction
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/form.json/status", method = RequestMethod.POST)
    public ResponseEntity<?> status(@RequestBody FormStatusRequest request) {
        return new ResponseEntity<>(this.forms.status(request), HttpStatus.OK);
    }

    @RequestMapping(value = "/form.json/upload", method = RequestMethod.POST)
    public ResponseEntity<?> upload(@RequestParam Long formId, @RequestParam String field, @RequestParam("file") MultipartFile file)
        throws IOException {
        if (file.getSize() > FormFields.LARGEST_UPLOAD_BYTES) {
            return new ResponseEntity<>(tooLarge(), HttpStatus.OK);
        }
        return new ResponseEntity<>(this.submissions.upload(formId, field, file.getOriginalFilename(), file.getBytes()), HttpStatus.OK);
    }

    /** A file larger than any form field takes, refused before it is read into memory (scale review P0 #4). */
    public static ResponseDto tooLarge() {
        return new ResponseDto(ProcessUtil.ERROR, String.format("A form takes files of at most %d MB.",
            FormFields.LARGEST_UPLOAD_BYTES / (1024 * 1024)));
    }

    @RequestMapping(value = "/form.json/submit", method = RequestMethod.POST)
    public ResponseEntity<?> submit(@RequestBody FormSubmitRequest request) {
        return new ResponseEntity<>(this.submissions.submit(request), HttpStatus.OK);
    }
}
