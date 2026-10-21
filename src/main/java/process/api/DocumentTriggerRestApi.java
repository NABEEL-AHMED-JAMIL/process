package process.api;

import org.barco.platform.security.BuilderAction;
import org.barco.platform.security.WorkspaceData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.documents.DocumentTriggerRequest;
import process.documents.DocumentTriggerService;

/**
 * A job's document trigger (MIG-364): start the job when a document of a type is approved in Document Intelligence.
 * Under sourceJob.json, which the gateway already sends to Core and PageAccessInterceptor gates as the jobs page -- no
 * new route or page key, as the inbox trigger (MIG-239):
 *
 * <ul>
 *   <li>GET sourceJob.json/documentTrigger?jobId= -- the job's trigger (configured, enabled, documentTypeId, batchSize, waiting);
 *       without jobId, every trigger of the workspace on a job the caller may see ({jobId, jobName, jobStatus, documentTypeId,
 *       enabled}, by type): what an approved document starts (UX review 2026-10-13, DI1);</li>
 *   <li>POST sourceJob.json/documentTrigger/save {jobId, documentTypeId, enabled, batchSize} -- set it;</li>
 *   <li>DELETE sourceJob.json/documentTrigger?jobId= -- remove it;</li>
 *   <li>GET sourceJob.json/documentApprovals?jobId=&amp;limit= -- what approved documents did to the job: Started (its
 *       run), Waiting (its place in the line) or Skipped (why), newest first.</li>
 * </ul>
 *
 * Whoever may see the job may set its trigger (JobOwnership); anyone else is answered as for a job that does not exist.
 */
// ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a schedule's document trigger and its approvals are a
// workspace's, "Work in this workspace"'s only.
@WorkspaceData
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class DocumentTriggerRestApi {

    private final DocumentTriggerService service;

    public DocumentTriggerRestApi(DocumentTriggerService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/documentTrigger", method = RequestMethod.GET)
    public ResponseEntity<?> trigger(@RequestParam(required = false) Long jobId) {
        return new ResponseEntity<>(jobId == null ? this.service.triggers() : this.service.trigger(jobId), HttpStatus.OK);
    }

    @BuilderAction
    @RequestMapping(value = "/sourceJob.json/documentTrigger/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody DocumentTriggerRequest request) {
        return new ResponseEntity<>(request == null ? this.service.trigger(null)
            : this.service.save(request.getJobId(), request.getDocumentTypeId(), request.getEnabled(), request.getBatchSize()), HttpStatus.OK);
    }

    @BuilderAction
    @RequestMapping(value = "/sourceJob.json/documentTrigger", method = RequestMethod.DELETE)
    public ResponseEntity<?> delete(@RequestParam Long jobId) {
        return new ResponseEntity<>(this.service.delete(jobId), HttpStatus.OK);
    }

    @RequestMapping(value = "/sourceJob.json/documentApprovals", method = RequestMethod.GET)
    public ResponseEntity<?> approvals(@RequestParam Long jobId, @RequestParam(required = false, defaultValue = "50") int limit) {
        return new ResponseEntity<>(this.service.approvals(jobId, limit), HttpStatus.OK);
    }
}
