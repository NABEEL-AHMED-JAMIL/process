package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.AiPromptDto;
import process.model.dto.ResponseDto;
import process.model.service.impl.AiPromptServiceImpl;
import process.model.service.impl.ObjectTextServiceImpl;
import process.util.ProcessUtil;
import process.model.dto.AiWorkerRunDto;

/**
 * Prompts. Reading is TENANT_USER -- a person must be able to see what the step on their
 * task says, and the file chat lists prompts -- writing and trying is TENANT_ADMIN, since a
 * try spends the workspace's tokens.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/aiPrompt.json")
public class AiPromptRestApi {

    private final Logger logger = LoggerFactory.getLogger(AiPromptRestApi.class);
    private final AiPromptServiceImpl service;
    private final ObjectTextServiceImpl objectText;

    public AiPromptRestApi(AiPromptServiceImpl service, ObjectTextServiceImpl objectText) { this.service = service; this.objectText = objectText; }

    /**
     * A file in a bucket as the text a variable can hold -- read the way the file chat reads
     * it, whatever the type. Try it fills a variable from it; the storage browser's own check
     * decides whether the caller may see the object at all.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/objectText", method = RequestMethod.GET)
    public ResponseEntity<?> objectText(@RequestParam String bucket, @RequestParam String key, @RequestParam(required = false) Integer maxChars) {
        try { return new ResponseEntity<>(this.objectText.read(bucket, key, maxChars), HttpStatus.OK); } catch (Exception ex) { return this.failed("objectText", ex); }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public ResponseEntity<?> list() {
        try { return new ResponseEntity<>(this.service.list(), HttpStatus.OK); } catch (Exception ex) { return this.failed("list", ex); }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public ResponseEntity<?> get(@RequestParam Long promptId) {
        try { return new ResponseEntity<>(this.service.get(promptId), HttpStatus.OK); } catch (Exception ex) { return this.failed("get", ex); }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody AiPromptDto dto) {
        try { return new ResponseEntity<>(this.service.save(dto), HttpStatus.OK); } catch (Exception ex) { return this.failed("save", ex); }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/setStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> setStatus(@RequestParam Long promptId, @RequestParam String status) {
        try { return new ResponseEntity<>(this.service.setStatus(promptId, status), HttpStatus.OK); } catch (Exception ex) { return this.failed("setStatus", ex); }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    public ResponseEntity<?> delete(@RequestParam Long promptId) {
        try { return new ResponseEntity<>(this.service.delete(promptId), HttpStatus.OK); } catch (Exception ex) { return this.failed("delete", ex); }
    }

    /** Runs the prompt as sent, with its samples (or `values`), and records a "try" run. */
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/try", method = RequestMethod.POST)
    public ResponseEntity<?> tryPrompt(@RequestBody AiPromptDto dto) {
        try { return new ResponseEntity<>(this.service.tryPrompt(dto), HttpStatus.OK); } catch (Exception ex) { return this.failed("try", ex); }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/runs", method = RequestMethod.GET)
    public ResponseEntity<?> runs(@RequestParam Long promptId, @RequestParam(required = false) Long page, @RequestParam(required = false) Long limit) {
        try { return new ResponseEntity<>(this.service.runs(promptId, page, limit), HttpStatus.OK); } catch (Exception ex) { return this.failed("runs", ex); }
    }

    /** The AI steps that ran for one job run -- the job history's card. */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/runsForJob", method = RequestMethod.GET)
    public ResponseEntity<?> runsForJob(@RequestParam Long jobQueueId) {
        try { return new ResponseEntity<>(this.service.runsForJob(jobQueueId), HttpStatus.OK); } catch (Exception ex) { return this.failed("runsForJob", ex); }
    }

    /** Calls, failures, tokens and latency per prompt over a range, for Reports. */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/usage", method = RequestMethod.GET)
    public ResponseEntity<?> usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        try { return new ResponseEntity<>(this.service.usage(from, to), HttpStatus.OK); } catch (Exception ex) { return this.failed("usage", ex); }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/versions", method = RequestMethod.GET)
    public ResponseEntity<?> versions(@RequestParam Long promptId) {
        try { return new ResponseEntity<>(this.service.versionsOf(promptId), HttpStatus.OK); } catch (Exception ex) { return this.failed("versions", ex); }
    }

    /**
     * The worker's call for an AI step handed to it: no user session, the run's own callback
     * token (X-Worker-Token, as on /changeState) is the proof. Open in SecurityConfig for that
     * reason; the service verifies the token before anything else.
     */
    @RequestMapping(value = "/run", method = RequestMethod.POST)
    public ResponseEntity<?> run(@RequestHeader(value = "X-Worker-Token", required = false) String workerToken,
        @RequestBody AiWorkerRunDto dto) {
        try {
            ResponseDto answer = this.service.runForWorker(dto, workerToken);
            if ("Unauthorized worker callback.".equals(answer.getMessage())) return new ResponseEntity<>(answer, HttpStatus.UNAUTHORIZED);
            return new ResponseEntity<>(answer, HttpStatus.OK);
        } catch (Exception ex) { return this.failed("run", ex); }
    }

    private ResponseEntity<?> failed(String what, Exception ex) {
        this.logger.error("An error occurred while {} prompt", what, ex);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
