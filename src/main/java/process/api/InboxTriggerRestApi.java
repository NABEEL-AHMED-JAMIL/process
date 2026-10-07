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
import process.inbox.InboxTriggerRequest;
import process.inbox.InboxTriggerService;

/**
 * A job's inbox trigger (MIG-239): start the job when a file arrives in its workspace's inbox. Under sourceJob.json,
 * which the gateway already sends to Core and PageAccessInterceptor gates as the jobs page -- no new route or page key:
 *
 * <ul>
 *   <li>GET sourceJob.json/inboxTrigger?jobId= -- the job's trigger (configured, enabled, filePattern);</li>
 *   <li>POST sourceJob.json/inboxTrigger/save {jobId, enabled, filePattern, batchSize} -- set it (filePattern: a glob on the
 *       file's name, blank for every file; batchSize, MIG-360: how many files that waited one run takes, 1 by default);</li>
 *   <li>DELETE sourceJob.json/inboxTrigger?jobId= -- remove it;</li>
 *   <li>GET sourceJob.json/inboxArrivals?jobId=&amp;limit= -- what the inbox's files did to the job: Started (its run),
 *       Waiting (MIG-360: its place in the line) or Skipped (why), newest first.</li>
 * </ul>
 *
 * Whoever may see the job may set its trigger (JobOwnership); anyone else is answered as for a job that does not exist.
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class InboxTriggerRestApi {

    private final InboxTriggerService service;

    public InboxTriggerRestApi(InboxTriggerService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/inboxTrigger", method = RequestMethod.GET)
    public ResponseEntity<?> trigger(@RequestParam Long jobId) {
        return new ResponseEntity<>(this.service.trigger(jobId), HttpStatus.OK);
    }

    @BuilderAction
    @RequestMapping(value = "/sourceJob.json/inboxTrigger/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody InboxTriggerRequest request) {
        return new ResponseEntity<>(request == null ? this.service.trigger(null)
            : this.service.save(request.getJobId(), request.getEnabled(), request.getFilePattern(), request.getBatchSize()), HttpStatus.OK);
    }

    @BuilderAction
    @RequestMapping(value = "/sourceJob.json/inboxTrigger", method = RequestMethod.DELETE)
    public ResponseEntity<?> delete(@RequestParam Long jobId) {
        return new ResponseEntity<>(this.service.delete(jobId), HttpStatus.OK);
    }

    @RequestMapping(value = "/sourceJob.json/inboxArrivals", method = RequestMethod.GET)
    public ResponseEntity<?> arrivals(@RequestParam Long jobId, @RequestParam(required = false, defaultValue = "50") int limit) {
        return new ResponseEntity<>(this.service.arrivals(jobId, limit), HttpStatus.OK);
    }
}
