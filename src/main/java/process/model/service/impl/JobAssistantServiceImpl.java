package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.JobAssistantRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.AiAgentService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.ProcessUtil;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Answers questions about one source job through a configured AI agent.
 *
 * The scope guarantee is structural rather than a plea in the prompt: the caller sends a jobId
 * and a question, and this service gathers the facts itself. The model is handed exactly one
 * job's record, schedule and runs, so it has nothing to say about any other job -- there is no
 * cross-job data in the context to leak. The instruction below closes the remaining gap, which
 * is the model guessing rather than admitting it was not told.
 *
 * The agent's API key is resolved and used here, never returned to the browser, following the
 * same path file chat uses.
 */
@Service
public class JobAssistantServiceImpl {

    /** Enough runs to characterise behaviour without burying the question in context. */
    private static final int RUN_SAMPLE = 40;
    private static final int MAX_FAILURE_REASONS = 6;

    private final Logger logger = LoggerFactory.getLogger(JobAssistantServiceImpl.class);

    private final SourceJobRepository sourceJobRepository;
    private final JobQueueRepository jobQueueRepository;
    private final SchedulerRepository schedulerRepository;
    private final AiAgentService aiAgentService;
    private final TenantFilterHelper tenantFilterHelper;

    @PersistenceContext
    private EntityManager entityManager;

    public JobAssistantServiceImpl(SourceJobRepository sourceJobRepository,
        JobQueueRepository jobQueueRepository, SchedulerRepository schedulerRepository,
        AiAgentService aiAgentService, TenantFilterHelper tenantFilterHelper) {
        this.sourceJobRepository = sourceJobRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.schedulerRepository = schedulerRepository;
        this.aiAgentService = aiAgentService;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    @Transactional(readOnly = true)
    public ResponseDto ask(JobAssistantRequestDto dto) throws Exception {
        if (ProcessUtil.isNull(dto.getJobId())) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, "jobId missing.");
        }
        if (ProcessUtil.isNull(dto.getAiAgentId())) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, "aiAgentId missing -- pick an AI agent first.");
        }
        if (ProcessUtil.isNull(dto.getMessage()) || dto.getMessage().trim().isEmpty()) {
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, "message missing.");
        }

        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> jobOpt = this.sourceJobRepository.findById(dto.getJobId());
        if (!jobOpt.isPresent() || !this.isOwnedByCaller(jobOpt.get())) {
            // Same wording as everywhere else: a refusal must not confirm the job exists.
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                String.format("SourceJob not found with %d.", dto.getJobId()));
        }
        SourceJob job = jobOpt.get();

        ResponseDto agentConfig = this.aiAgentService.resolveRuntimeConfig(dto.getAiAgentId());
        if (!ProcessUtil.SUCCESS.equals(agentConfig.getStatus())) {
            return agentConfig;
        }
        AiAgentRuntimeConfigDto config = (AiAgentRuntimeConfigDto) agentConfig.getData();

        AdHocPromptRequestDto prompt = new AdHocPromptRequestDto();
        prompt.setProvider(config.getProvider());
        prompt.setModel(config.getModel());
        prompt.setApiKey(config.getApiKey());
        prompt.setApiEndpoint(config.getApiEndpoint());
        prompt.setInstructions(this.buildInstructions(job, dto.getHistory()));
        prompt.setText(dto.getMessage());

        try {
            ResponseDto response = this.aiAgentService.processAdHoc(prompt);
            if (!ProcessUtil.SUCCESS.equals(response.getStatus())) {
                return response;
            }
            return new ResponseDto(ProcessUtil.SUCCESS, "Replied.", response.getData());
        } catch (Exception ex) {
            this.logger.error("Job assistant failed for job {}", dto.getJobId(), ex);
            return new ResponseDto(ProcessUtil.ERROR_MESSAGE, "The assistant didn't respond: " + ex.getMessage());
        }
    }

    private boolean isOwnedByCaller(SourceJob job) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return job != null && Objects.equals(job.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Everything the model is allowed to know, and the rule for what to do when it is asked
     * something outside that. One job's facts, nothing else.
     */
    private String buildInstructions(SourceJob job, List<String> history) {
        List<JobQueue> runs = this.jobQueueRepository.findAllByJobId(job.getJobId());
        Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(job.getJobId());
        SourceTask task = job.getTaskDetail();

        StringBuilder out = new StringBuilder();
        out.append("You are an assistant for ONE scheduled ETL job in a job-scheduling console.\n")
           .append("You may only discuss the job described below. Rules:\n")
           .append("- If asked about any other job, task, tenant or user, reply that you can only ")
           .append("discuss job #").append(job.getJobId()).append(" and stop.\n")
           .append("- Use only the facts below. If a fact is not here, say you do not have it. ")
           .append("Never estimate, infer or invent a number, date, file name or folder.\n")
           .append("- Be brief and concrete. Quote the real figures.\n\n");

        out.append("== JOB ==\n")
           .append("Id: ").append(job.getJobId()).append('\n')
           .append("Name: ").append(job.getJobName()).append('\n')
           .append("State: ").append(job.getJobStatus()).append('\n')
           .append("Last run status: ").append(job.getJobRunningStatus()).append('\n')
           .append("Runs: ").append(job.getExecution()).append('\n')
           .append("Priority: ").append(job.getPriority()).append('\n');
        if (!ProcessUtil.isNull(job.getLastJobRun())) {
            out.append("Last run at: ").append(job.getLastJobRun()).append('\n');
        }
        out.append("Emails on complete/fail/skip: ").append(job.isCompleteJob()).append('/')
           .append(job.isFailJob()).append('/').append(job.isSkipJob()).append('\n');

        if (task != null) {
            out.append("\n== TASK IT RUNS ==\n")
               .append("Task: ").append(task.getTaskName()).append(" (id ").append(task.getTaskDetailId()).append(")\n");
            if (task.getSourceTaskType() != null) {
                out.append("Type: ").append(task.getSourceTaskType().getServiceName()).append('\n')
                   .append("Kafka target: ").append(task.getSourceTaskType().getQueueTopicPartition()).append('\n');
            }
            out.append("Bucket: ").append(nullSafe(task.getBucket())).append('\n')
               .append("Reads from: ").append(nullSafe(task.getInputFolder())).append('\n')
               .append("Writes to: ").append(nullSafe(task.getOutputFolder())).append('\n')
               .append("Pipeline: ").append(nullSafe(task.getPipelineId())).append('\n');
        }

        scheduler.ifPresent(s -> out.append("\n== SCHEDULE ==\n")
            .append("Frequency: ").append(nullSafe(s.getFrequency()))
            .append(" every ").append(nullSafe(s.getIntervalValue())).append('\n')
            .append("Starts: ").append(s.getStartDate()).append(' ').append(s.getStartTime()).append('\n')
            .append("Ends: ").append(s.getEndDate() == null ? "no end date" : s.getEndDate()).append('\n')
            .append("Next run: ").append(s.isExpired() ? "expired, will not run again" : nullSafe(String.valueOf(s.getNextRunAt()))).append('\n'));

        out.append("\n== RUN HISTORY ==\n");
        if (runs.isEmpty()) {
            out.append("This job has never run.\n");
        } else {
            Map<String, Long> byStatus = runs.stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.getJobStatus()), Collectors.counting()));
            out.append("Total runs: ").append(runs.size()).append('\n')
               .append("By status: ").append(byStatus).append('\n');

            OptionalDouble average = runs.stream()
                .filter(r -> r.getStartTime() != null && r.getEndTime() != null)
                .mapToLong(r -> Duration.between(r.getStartTime(), r.getEndTime()).getSeconds())
                .average();
            if (average.isPresent()) {
                out.append("Average duration: ").append(Math.round(average.getAsDouble())).append("s\n");
            }

            Map<String, Long> failureReasons = runs.stream()
                .filter(r -> "Failed".equals(String.valueOf(r.getJobStatus())))
                .map(r -> ProcessUtil.isNull(r.getJobStatusMessage()) ? "no message" : r.getJobStatusMessage())
                .collect(Collectors.groupingBy(m -> m, Collectors.counting()));
            if (!failureReasons.isEmpty()) {
                out.append("Failure reasons (message: count):\n");
                failureReasons.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_FAILURE_REASONS)
                    .forEach(e -> out.append("  ").append(e.getValue()).append("x ").append(e.getKey()).append('\n'));
            }

            out.append("Most recent runs (id, status, started, ended, message):\n");
            runs.stream()
                .sorted(Comparator.comparing(JobQueue::getDateCreated,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RUN_SAMPLE)
                .forEach(r -> out.append("  #").append(r.getJobQueueId()).append(' ')
                    .append(r.getJobStatus()).append(' ')
                    .append(r.getStartTime()).append(" -> ").append(r.getEndTime()).append(" | ")
                    .append(nullSafe(r.getJobStatusMessage())).append('\n'));
        }

        if (history != null && !history.isEmpty()) {
            out.append("\n== EARLIER IN THIS CONVERSATION ==\n");
            history.forEach(line -> out.append(line).append('\n'));
        }
        return out.toString();
    }

    private static String nullSafe(String value) {
        return ProcessUtil.isNull(value) ? "not set" : value;
    }

}
