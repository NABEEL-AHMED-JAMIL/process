package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.settings.RunConfigResolver;
import process.util.ProcessUtil;

import java.util.List;

/**
 * Where a worker fetches its run's configuration (MIG-167): POST /runConfig.json/resolve with the run's
 * X-Worker-Token and {jobId, jobQueueId, config: [KEY...], secrets: [KEY...]}. Outside the JWT chain like the other
 * worker callbacks (SecurityConfig); the token is the proof, and RunConfigResolver decides what it proves. No CORS:
 * no browser has any business calling it.
 */
@RestController
@RequestMapping(value = "/runConfig.json")
public class RunConfigRestApi {

    /** The request. Keys only; a value never travels this way. */
    public static class ResolveRequest {
        public Long jobId;
        public Long jobQueueId;
        public List<String> config;
        public List<String> secrets;
    }

    private final Logger logger = LoggerFactory.getLogger(RunConfigRestApi.class);
    private final RunConfigResolver resolver;

    public RunConfigRestApi(RunConfigResolver resolver) {
        this.resolver = resolver;
    }

    @PostMapping(value = "/resolve")
    public ResponseEntity<ResponseDto> resolve(@RequestHeader(value = "X-Worker-Token", required = false) String token,
        @RequestBody(required = false) ResolveRequest request) {
        if (request == null) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "A body is required."), HttpStatus.BAD_REQUEST);
        }
        try {
            return this.resolver.resolve(token, request.jobId, request.jobQueueId, request.config, request.secrets);
        } catch (RuntimeException failed) {
            // The class and the run only: an exception's message could carry what was being read.
            this.logger.error("Configuration request for run {} failed: {}", request.jobQueueId, failed.getClass().getSimpleName());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
