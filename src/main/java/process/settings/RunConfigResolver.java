package process.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.PipelineConfig;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.security.RunCallbackTokens;
import process.util.EncryptionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * What a worker asks Core for at run time: the values of the ${config:KEY} and ${secret:KEY} its run's task
 * references (MIG-167). One mechanism for both kinds -- nothing is substituted into the payload at dispatch, so no
 * value is ever in the job's payload, the Kafka message or the dispatch outbox, and an edited value is what the next
 * run gets.
 *
 * Every answer is bounded by the run the caller proved with its X-Worker-Token (RunCallbackTokens, the proof the
 * worker callbacks use):
 * - that run only, and only while it is Start or Running -- a finished, queued or retried-away run gets nothing;
 * - that run's workspace only -- the tenant comes from the run's job, never from the request;
 * - only keys the run's task payload references, as the kind it references them.
 * Every secret handed out is written to the run's audit log by KEY NAME. No value -- nor the token, nor a sealed
 * form -- is ever written to a log here (RunConfigResolverTest.valuesAreNeverLogged).
 */
@Service
public class RunConfigResolver {

    static final String UNAUTHORIZED = "Unauthorized worker callback.";
    static final int MAX_KEYS = 100;

    private static final Logger logger = LoggerFactory.getLogger(RunConfigResolver.class);
    private static final EnumSet<JobStatus> LIVE = EnumSet.of(JobStatus.Start, JobStatus.Running);

    private final RunCallbackTokens tokens;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final PipelineConfigRepository entries;
    private final EncryptionUtil encryption;
    private final TransactionServiceImpl audit;

    public RunConfigResolver(RunCallbackTokens tokens, JobQueueRepository runs, SourceJobRepository jobs,
        PipelineConfigRepository entries, EncryptionUtil encryption, TransactionServiceImpl audit) {
        this.tokens = tokens;
        this.runs = runs;
        this.jobs = jobs;
        this.entries = entries;
        this.encryption = encryption;
        this.audit = audit;
    }

    @Transactional
    public ResponseEntity<ResponseDto> resolve(String token, Long jobId, Long jobQueueId, List<String> config, List<String> secrets) {
        List<String> configKeys = config == null ? Collections.emptyList() : config;
        List<String> secretKeys = secrets == null ? Collections.emptyList() : secrets;
        if (jobId == null || jobQueueId == null) {
            return bad("jobId and jobQueueId are required.");
        }
        if (configKeys.isEmpty() && secretKeys.isEmpty()) {
            return bad("Name at least one key in config or secrets.");
        }
        if (configKeys.size() + secretKeys.size() > MAX_KEYS) {
            return bad(String.format("At most %d keys in one request.", MAX_KEYS));
        }
        for (String key : concat(configKeys, secretKeys)) {
            if (key == null || !ConfigReferences.KEY.matcher(key).matches()) {
                return bad("Every key is UPPER_SNAKE: A-Z, 0-9 and _, starting with a letter, at most 64 characters.");
            }
        }

        Optional<RunCallbackTokens.Refusal> refused = this.tokens.verify(jobId, jobQueueId, token);
        if (refused.isPresent()) {
            logger.warn("Refused a configuration request for job {} run {}: {}.", jobId, jobQueueId, refused.get());
            return unauthorized();
        }
        Optional<JobQueue> run = this.runs.findById(jobQueueId);
        if (!run.isPresent() || !LIVE.contains(run.get().getJobStatus())) {
            logger.warn("Refused a configuration request for job {} run {}: the run is {}.", jobId, jobQueueId,
                run.map(JobQueue::getJobStatus).orElse(null));
            return unauthorized();
        }
        Optional<SourceJob> job = this.jobs.findById(run.get().getJobId());
        Long tenantId = job.map(SourceJob::getTenantId).orElse(null);
        if (!job.isPresent() || tenantId == null || job.get().getTaskDetail() == null) {
            logger.warn("Refused a configuration request for job {} run {}: the run belongs to no workspace or task.", jobId, jobQueueId);
            return unauthorized();
        }

        // The task as it is stored: the prepared payload may carry an AI step's answers, and those must not be able
        // to name a key the task itself does not.
        Set<ConfigReferences.Reference> referenced = ConfigReferences.in(job.get().getTaskDetail().getTaskPayload());
        for (String key : configKeys) {
            if (!referenced.contains(ConfigReferences.Reference.config(key))) {
                return this.notReferenced(jobQueueId, key, "config");
            }
        }
        for (String key : secretKeys) {
            if (!referenced.contains(ConfigReferences.Reference.secret(key))) {
                return this.notReferenced(jobQueueId, key, "secret");
            }
        }

        Map<String, PipelineConfig> byKey = new LinkedHashMap<>();
        for (PipelineConfig entry : this.entries.findForRun(tenantId, concat(configKeys, secretKeys))) {
            if (tenantId.equals(entry.getTenantId())) {
                byKey.put(entry.getConfigKey(), entry);
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : configKeys) {
            PipelineConfig entry = byKey.get(key);
            if (entry == null || entry.isSecret()) {
                return missing(jobQueueId, key);
            }
            values.put(key, entry.getValue());
        }
        Map<String, String> opened = new LinkedHashMap<>();
        for (String key : secretKeys) {
            PipelineConfig entry = byKey.get(key);
            if (entry == null || !entry.isSecret()) {
                return missing(jobQueueId, key);
            }
            try {
                opened.put(key, this.encryption.decrypt(entry.getValueSealed()));
            } catch (IllegalStateException unreadable) {
                logger.error("Run {}: secret {} of workspace {} opens with no key process holds.", jobQueueId, key, tenantId);
                return failed(String.format("Secret %s cannot be opened; enter it again in Configuration values.", key));
            }
        }

        if (!opened.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (String key : opened.keySet()) {
                lines.add(String.format("Secret %s was read by the worker.", key));
            }
            this.audit.saveJobAuditLogs(jobId, jobQueueId, lines);
        }
        logger.info("Run {} of job {} (workspace {}) read configuration {} and secrets {}.", jobQueueId, jobId, tenantId,
            values.keySet(), opened.keySet());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", values);
        data.put("secrets", opened);
        return new ResponseEntity<>(new ResponseDto(SUCCESS, "Configuration resolved.", data), HttpStatus.OK);
    }

    private ResponseEntity<ResponseDto> notReferenced(Long jobQueueId, String key, String kind) {
        logger.warn("Run {} asked for {} {}, which its task does not reference as ${{}:{}}.", jobQueueId, kind, key, kind, key);
        return bad(String.format("%s is not referenced as ${%s:%s} by this run's task.", key, kind, key));
    }

    private static ResponseEntity<ResponseDto> missing(Long jobQueueId, String key) {
        logger.warn("Run {} asked for {}, which is not set in its workspace.", jobQueueId, key);
        return failed(String.format("Configuration %s is not set in this workspace.", key));
    }

    private static ResponseEntity<ResponseDto> failed(String sentence) {
        return new ResponseEntity<>(new ResponseDto(ERROR, sentence), HttpStatus.OK);
    }

    private static ResponseEntity<ResponseDto> bad(String sentence) {
        return new ResponseEntity<>(new ResponseDto(ERROR, sentence), HttpStatus.BAD_REQUEST);
    }

    private static ResponseEntity<ResponseDto> unauthorized() {
        return new ResponseEntity<>(new ResponseDto(ERROR, UNAUTHORIZED), HttpStatus.UNAUTHORIZED);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }
}
