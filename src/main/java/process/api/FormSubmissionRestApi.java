package process.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.forms.FormSubmissionService;
import process.model.dto.ResponseDto;

import static process.util.ProcessUtil.ERROR;

/**
 * Wave 5 Forms (lite): what a workspace's forms collected, for whoever holds the page 'form-submissions'
 * (PageKey.FORM_SUBMISSIONS gates /formSubmission.json for a tenant user). Reads only:
 *
 * <ul>
 *   <li>GET formSubmission.json/list?formId=&amp;limit= -- a form's submissions, newest first: who, when, and whether each
 *       started its run (the run's id) or not (why);</li>
 *   <li>GET formSubmission.json/fetch?submissionId= -- one, with its answers;</li>
 *   <li>GET formSubmission.json/export?formId= -- all of a form's submissions as CSV.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*", exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION})
@PreAuthorize("hasRole('TENANT_USER')")
public class FormSubmissionRestApi {

    private final FormSubmissionService submissions;

    public FormSubmissionRestApi(FormSubmissionService submissions) {
        this.submissions = submissions;
    }

    @RequestMapping(value = "/formSubmission.json/list", method = RequestMethod.GET)
    public ResponseEntity<?> list(@RequestParam Long formId, @RequestParam(required = false, defaultValue = "100") int limit) {
        return new ResponseEntity<>(this.submissions.list(formId, limit), HttpStatus.OK);
    }

    @RequestMapping(value = "/formSubmission.json/fetch", method = RequestMethod.GET)
    public ResponseEntity<?> fetch(@RequestParam Long submissionId) {
        return new ResponseEntity<>(this.submissions.fetch(submissionId), HttpStatus.OK);
    }

    @RequestMapping(value = "/formSubmission.json/export", method = RequestMethod.GET)
    public ResponseEntity<?> export(@RequestParam Long formId) {
        FormSubmissionService.Export export = this.submissions.export(formId);
        if (export == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(new ResponseDto(ERROR, "Form not found."));
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .body(export.content);
    }
}
