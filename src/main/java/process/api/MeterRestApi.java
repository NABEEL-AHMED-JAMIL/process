package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the metering service asks the console: is this token the live token of this run, and
 * whose run is it. The token itself is the proof (as on /changeState), so the route is open;
 * the answer names the workspace and nothing else.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/meter.json")
public class MeterRestApi {

    private final Logger logger = LoggerFactory.getLogger(MeterRestApi.class);
    private final RunCallbackTokens runTokens;
    private final JobQueueRepository jobQueues;
    private final SourceJobRepository jobs;

    public MeterRestApi(RunCallbackTokens runTokens, JobQueueRepository jobQueues, SourceJobRepository jobs) {
        this.runTokens = runTokens; this.jobQueues = jobQueues; this.jobs = jobs;
    }

    public static class VerifyRunDto {
        public Long jobId;
        public Long jobQueueId;
    }

    @RequestMapping(value = "/verifyRun", method = RequestMethod.POST)
    public ResponseEntity<?> verifyRun(@RequestHeader(value = "X-Worker-Token", required = false) String token, @RequestBody VerifyRunDto dto) {
        Optional<RunCallbackTokens.Refusal> refused = this.runTokens.verify(dto.jobId, dto.jobQueueId, token);
        if (refused.isPresent()) {
            this.logger.warn("meter verifyRun refused for run {}: {}", dto.jobQueueId, refused.get());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "Unauthorized worker callback."), HttpStatus.UNAUTHORIZED);
        }
        Optional<JobQueue> run = this.jobQueues.findById(dto.jobQueueId);
        Optional<SourceJob> job = run.flatMap(r -> this.jobs.findById(r.getJobId()));
        if (!job.isPresent() || job.get().getTenantId() == null) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "This run belongs to no workspace."), HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("tenantId", job.get().getTenantId());
        data.put("jobId", dto.jobId);
        data.put("jobQueueId", dto.jobQueueId);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Run verified.", data), HttpStatus.OK);
    }
}
