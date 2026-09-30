package process.api;

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
import process.forms.FormSaveRequest;
import process.forms.FormService;
import process.forms.FormStatusRequest;
import process.forms.FormSubmissionService;
import process.forms.FormSubmitRequest;

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

    public FormRestApi(FormService forms, FormSubmissionService submissions) {
        this.forms = forms;
        this.submissions = submissions;
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

    @RequestMapping(value = "/form.json/submit", method = RequestMethod.POST)
    public ResponseEntity<?> submit(@RequestBody FormSubmitRequest request) {
        return new ResponseEntity<>(this.submissions.submit(request), HttpStatus.OK);
    }
}
