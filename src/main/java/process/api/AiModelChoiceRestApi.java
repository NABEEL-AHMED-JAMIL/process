package process.api;

import org.barco.platform.security.BuilderAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.ai.AiModelChoiceService;
import process.engine.OneRunInFlight;
import process.model.dto.AiModelChoiceDto;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * The AI model a job's steps run on (Wave 4, MIG-242's Core part), under the prefixes the gateway already sends to
 * Core and gates as the jobs and tasks pages -- so no gateway route or page key is new:
 *
 * <ul>
 *   <li>sourceJob.json/aiModelChoice (GET, POST /save): a job's AI steps, their schedule setting and allowed models;</li>
 *   <li>sourceJob.json/runSourceJobWith: "Run with..." -- Run now, with chosen models for this run only;</li>
 *   <li>sourceJob.json/aiSteps: what each AI step of a run asked for and ran on (the run's manifest);</li>
 *   <li>sourceTask.json/aiStepModelOptions (GET, POST /save): a pipeline step's own allowed list, for task editors.</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class AiModelChoiceRestApi {

    private final Logger logger = LoggerFactory.getLogger(AiModelChoiceRestApi.class);

    private final AiModelChoiceService service;

    public AiModelChoiceRestApi(AiModelChoiceService service) {
        this.service = service;
    }

    @RequestMapping(value = "/sourceJob.json/aiModelChoice", method = RequestMethod.GET)
    public ResponseEntity<?> jobChoices(@RequestParam Long jobId) {
        return new ResponseEntity<>(this.service.jobChoices(jobId), HttpStatus.OK);
    }

    @BuilderAction
    @RequestMapping(value = "/sourceJob.json/aiModelChoice/save", method = RequestMethod.POST)
    public ResponseEntity<?> saveSchedule(@RequestBody AiModelChoiceDto request) {
        return new ResponseEntity<>(this.service.saveSchedule(request), HttpStatus.OK);
    }

    // Owner 2026-09-29: not a @BuilderAction -- a MANAGED workspace's customers run their existing schedules too.
    @RequestMapping(value = "/sourceJob.json/runSourceJobWith", method = RequestMethod.POST)
    public ResponseEntity<?> runWith(@RequestBody AiModelChoiceDto request) {
        try {
            return new ResponseEntity<>(this.service.runWith(request), HttpStatus.OK);
        } catch (Exception ex) {
            if (OneRunInFlight.isViolation(ex)) {
                // As runSourceJob answers it: another Run or the enqueuer took the slot first.
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR,
                    "A job can't be run while its last run is still in flight ('Queue', 'Start', 'Running')."), HttpStatus.OK);
            }
            this.logger.error("An error occurred while runSourceJobWith.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/sourceJob.json/aiSteps", method = RequestMethod.GET)
    public ResponseEntity<?> runSteps(@RequestParam Long jobQueueId) {
        return new ResponseEntity<>(this.service.runSteps(jobQueueId), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/sourceTask.json/aiStepModelOptions", method = RequestMethod.GET)
    public ResponseEntity<?> stepOptions(@RequestParam Long taskDetailId, @RequestParam String stepKey) {
        return new ResponseEntity<>(this.service.stepOptions(taskDetailId, stepKey), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @BuilderAction
    @RequestMapping(value = "/sourceTask.json/aiStepModelOptions/save", method = RequestMethod.POST)
    public ResponseEntity<?> saveStepOptions(@RequestBody AiModelChoiceDto.StepOptions request) {
        return new ResponseEntity<>(this.service.saveStepOptions(request), HttpStatus.OK);
    }
}
