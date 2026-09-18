package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.ai.PromptRunner;
import process.ai.AiStepService;
import process.model.dto.AiWorkerRunDto;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.RunCallbackTokens;
import process.model.dto.AiPromptDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.model.pojo.AiPromptRun;
import process.model.pojo.AiPromptVersion;
import process.model.pojo.Tenant;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.AiPromptVersionRepository;
import process.model.repository.TenantRepository;
import process.model.repository.PipelineRepository;
import process.security.TenantContext;
import process.util.PagingUtil;
import process.util.UserNameResolver;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import org.slf4j.LoggerFactory;
import process.model.projection.AiUsageProjection;

/**
 * Prompts: what a step says to a model. Scoped like pipelines -- a tenant's own, a platform
 * admin's every workspace's, a platform admin's new rows filed under the workspace named.
 * Every save is a version; Try it runs the draft as sent (saved or not) with the samples;
 * a real run pins a version. The connection is resolved here, the call is made by
 * {@link PromptRunner}, so the editor and a pipeline step never disagree.
 */
@Service
public class AiPromptServiceImpl {

    private final AiPromptRepository prompts;
    private final AiPromptVersionRepository versions;
    private final AiPromptRunRepository runs;
    private final TenantRepository tenants;
    private final PipelineRepository pipelines;
    private final AiModelConnectionServiceImpl connections;
    private final AiStepService steps;
    private final RunCallbackTokens runTokens;
    private final JobQueueRepository jobQueues;
    private final SourceJobRepository jobs;
    private final PromptRunner runner;
    private final UserNameResolver userNameResolver;
    private final Gson gson = new Gson();

    public AiPromptServiceImpl(AiPromptRepository prompts, AiPromptVersionRepository versions, AiPromptRunRepository runs,
        TenantRepository tenants, PipelineRepository pipelines, AiModelConnectionServiceImpl connections, PromptRunner runner, UserNameResolver userNameResolver,
        AiStepService steps, RunCallbackTokens runTokens, JobQueueRepository jobQueues, SourceJobRepository jobs) {
        this.prompts = prompts; this.versions = versions; this.runs = runs; this.tenants = tenants; this.pipelines = pipelines;
        this.steps = steps; this.runTokens = runTokens; this.jobQueues = jobQueues; this.jobs = jobs;
        this.connections = connections; this.runner = runner; this.userNameResolver = userNameResolver;
    }

    public ResponseDto list() {
        List<AiPrompt> visible = TenantContext.isPlatformAdmin()
            ? this.prompts.findVisibleToPlatformAdmin(Status.Delete)
            : this.prompts.findVisibleToTenant(TenantContext.getTenantId(), Status.Delete);
        this.userNameResolver.attachNames(visible);
        Map<Long, String> tenantNames = new HashMap<>();
        if (TenantContext.isPlatformAdmin()) for (Tenant t : this.tenants.findAll()) tenantNames.put(t.getTenantId(), t.getTenantName());
        List<AiPromptDto> rows = visible.stream().map(p -> this.toDto(p, tenantNames, true)).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, String.format("%d prompt(s).", rows.size()), rows);
    }

    public ResponseDto get(Long promptId) {
        Optional<AiPrompt> found = this.scopedFind(promptId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", promptId));
        this.userNameResolver.attachNames(found.get());
        return new ResponseDto(SUCCESS, "Data found.", this.toDto(found.get(), Collections.emptyMap(), true));
    }

    /** Every visible prompt the caller may use, as entities -- for the agent aliases. */
    public List<AiPrompt> visible() {
        return TenantContext.isPlatformAdmin()
            ? this.prompts.findVisibleToPlatformAdmin(Status.Delete)
            : this.prompts.findVisibleToTenant(TenantContext.getTenantId(), Status.Delete);
    }

    public Optional<AiPrompt> scopedFind(Long promptId) {
        if (isNull(promptId)) return Optional.empty();
        Optional<AiPrompt> found = this.prompts.findById(promptId).filter(p -> p.getStatus() != Status.Delete);
        if (!found.isPresent() || TenantContext.isPlatformAdmin()) return found;
        Long owner = found.get().getTenantId();
        return owner != null && owner.equals(TenantContext.getTenantId()) ? found : Optional.empty();
    }

    public Optional<AiPrompt> scopedFindByUuid(String promptUuid) {
        if (isNull(promptUuid) || promptUuid.trim().isEmpty()) return Optional.empty();
        return this.prompts.findByPromptUuid(promptUuid.trim()).flatMap(p -> this.scopedFind(p.getPromptId()));
    }

    /** Create, or a new version of an existing prompt; `activate` makes it the live one. */
    @Transactional
    public ResponseDto save(AiPromptDto dto) {
        ResponseDto refused = this.validate(dto);
        if (refused != null) return refused;
        AiPrompt p;
        if (dto.getPromptId() != null) {
            Optional<AiPrompt> existing = this.scopedFind(dto.getPromptId());
            if (!existing.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", dto.getPromptId()));
            p = existing.get();
            p.setVersion(p.getVersion() + 1);
        } else {
            p = new AiPrompt();
            p.setPromptUuid(UUID.randomUUID().toString());
            p.setTenantId(TenantContext.isPlatformAdmin() ? dto.getTenantId() : TenantContext.getTenantId());
            p.setVersion(1);
            p.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        if (dto.getConnectionId() != null) {
            Optional<AiModelConnection> c = this.connections.scopedFind(dto.getConnectionId());
            if (!c.isPresent()) return new ResponseDto(ERROR, "That model connection is not one this workspace can use.");
            if (!Objects.equals(c.get().getTenantId(), p.getTenantId()) && !TenantContext.isPlatformAdmin()) {
                return new ResponseDto(ERROR, "That model connection belongs to another workspace.");
            }
        }
        p.setName(dto.getName().trim());
        p.setDescription(dto.getDescription());
        p.setConnectionId(dto.getConnectionId());
        p.setModel(isNull(dto.getModel()) || dto.getModel().trim().isEmpty() ? null : dto.getModel().trim());
        p.setSystemInstructions(dto.getSystemInstructions());
        p.setUserTemplate(dto.getUserTemplate());
        p.setVariables(this.gson.toJson(this.variablesOf(dto)));
        p.setOutputMode("json".equals(dto.getOutputMode()) ? "json" : "text");
        p.setOutputSchema("json".equals(p.getOutputMode()) ? dto.getOutputSchema() : null);
        p.setTemperature(dto.getTemperature());
        p.setMaxTokens(dto.getMaxTokens());
        p.setTags(dto.getTags());
        if (Boolean.TRUE.equals(dto.getActivate())) p.setStatus(Status.Active);
        p = this.prompts.save(p);
        this.versions.save(AiPromptVersion.of(p, TenantContext.getAppUserId()));
        return new ResponseDto(SUCCESS, String.format("\"%s\" saved as v%d%s.", p.getName(), p.getVersion(),
            p.getStatus() == Status.Active ? " and active" : ""), this.toDto(p, Collections.emptyMap(), false));
    }

    @Transactional
    public ResponseDto setStatus(Long promptId, String status) {
        Optional<AiPrompt> found = this.scopedFind(promptId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", promptId));
        Status next = "Active".equals(status) ? Status.Active : Status.Inactive;
        if (next == Status.Inactive) {
            long using = this.pipelines.countUsingPrompt(promptId);
            if (using > 0) return new ResponseDto(ERROR, String.format("%d pipeline(s) run this prompt as a step. Take the step off them first.", using));
        }
        found.get().setStatus(next);
        this.prompts.save(found.get());
        return new ResponseDto(SUCCESS, String.format("\"%s\" is now %s.", found.get().getName(), next == Status.Active ? "active" : "inactive"));
    }

    @Transactional
    public ResponseDto delete(Long promptId) {
        Optional<AiPrompt> found = this.scopedFind(promptId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", promptId));
        long using = this.pipelines.countUsingPrompt(promptId);
        if (using > 0) return new ResponseDto(ERROR, String.format("%d pipeline(s) run this prompt as a step. Take the step off them first.", using));
        found.get().setStatus(Status.Delete);
        this.prompts.save(found.get());
        return new ResponseDto(SUCCESS, "Prompt deleted. Runs already recorded keep their history.");
    }

    /**
     * Runs the prompt as sent -- saved or still a draft in the editor -- with the values given
     * or, failing those, the variables' samples. Recorded as a "try" run so cost shows up.
     */
    public ResponseDto tryPrompt(AiPromptDto dto) {
        ResponseDto refused = this.validate(dto);
        if (refused != null) return refused;
        Long tenantId = TenantContext.isPlatformAdmin() ? dto.getTenantId() : TenantContext.getTenantId();
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = tenantId; job.promptId = dto.getPromptId(); job.promptVersion = dto.getVersion();
        job.kind = "try"; job.actor = TenantContext.getAppUserId();
        Optional<AiModelConnection> c = dto.getConnectionId() != null ? this.connections.scopedFind(dto.getConnectionId()) : this.connections.defaultFor(tenantId);
        if (!c.isPresent()) return new ResponseDto(ERROR, "No model connection to run on: pick one, or set a workspace default.");
        job.connection = c.get(); job.apiKey = this.connections.keyOf(c.get());
        job.model = isNull(dto.getModel()) || dto.getModel().trim().isEmpty() ? c.get().getDefaultModel() : dto.getModel().trim();
        job.system = dto.getSystemInstructions(); job.template = dto.getUserTemplate();
        job.variables = this.variablesOf(dto);
        for (AiPromptDto.Variable v : job.variables) if (v.sample != null) job.values.put(v.name, v.sample);
        if (dto.getValues() != null) job.values.putAll(dto.getValues());
        job.outputMode = "json".equals(dto.getOutputMode()) ? "json" : "text"; job.outputSchema = dto.getOutputSchema();
        job.temperature = dto.getTemperature(); job.maxTokens = dto.getMaxTokens();
        AiPromptRun run = this.runner.run(job);
        return new ResponseDto("ok".equals(run.getStatus()) ? SUCCESS : ERROR,
            "ok".equals(run.getStatus()) ? String.format("Answered in %.1f s.", run.getLatencyMs() / 1000.0) : run.getError(), run);
    }

    public ResponseDto runs(Long promptId, Long page, Long limit) {
        Optional<AiPrompt> found = this.scopedFind(promptId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", promptId));
        PageRequest window = PageRequest.of(page == null || page < 1 ? 0 : (int) (page - 1), limit == null || limit < 1 ? 20 : (int) Math.min(limit, 200));
        Page<AiPromptRun> found2 = this.runs.findAllByPromptIdOrderByRunIdDesc(promptId, window);
        return new ResponseDto(SUCCESS, String.format("%d run(s).", found2.getTotalElements()), found2.getContent(),
            PagingUtil.convertEntityToPagingDTO(found2.getTotalElements(), window));
    }

    /** The AI steps that ran for one job run, for its history; scoped to the caller's workspace. */
    public ResponseDto runsForJob(Long jobQueueId) {
        if (isNull(jobQueueId)) return new ResponseDto(ERROR, "jobQueueId missing.");
        List<AiPromptRun> rows = this.runs.findAllByJobQueueIdOrderByRunIdAsc(jobQueueId).stream()
            .filter(r -> TenantContext.isPlatformAdmin() || Objects.equals(r.getTenantId(), TenantContext.getTenantId()))
            .collect(Collectors.toList());
        Map<Long, String> names = new HashMap<>();
        for (AiPromptRun r : rows) if (r.getPromptId() != null && !names.containsKey(r.getPromptId())) {
            names.put(r.getPromptId(), this.prompts.findById(r.getPromptId()).map(AiPrompt::getName).orElse("prompt " + r.getPromptId()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiPromptRun r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("run", r); m.put("promptName", names.get(r.getPromptId()));
            out.add(m);
        }
        return new ResponseDto(SUCCESS, String.format("%d AI step(s).", out.size()), out);
    }

    /**
     * A worker's call for an AI step handed to it in the task's document. The proof is the
     * run's own callback token, so a caller can only run the steps of the run it was handed;
     * the step has to be one the job's pipeline hands to the worker and name this prompt.
     * Every refusal reads the same to the caller; which check failed is logged.
     */
    public ResponseDto runForWorker(AiWorkerRunDto dto, String presentedToken) {
        if (dto == null || dto.getJobQueueId() == null || dto.getStepTag() == null || dto.getPromptUuid() == null) {
            return new ResponseDto(ERROR, "jobId, jobQueueId, promptUuid and stepTag are required.");
        }
        Optional<RunCallbackTokens.Refusal> refused = this.runTokens.verify(dto.getJobId(), dto.getJobQueueId(), presentedToken);
        if (refused.isPresent()) {
            LoggerFactory.getLogger(AiPromptServiceImpl.class).warn("Rejected worker AI step for job {} run {}: {}.", dto.getJobId(), dto.getJobQueueId(), refused.get());
            return new ResponseDto(ERROR, "Unauthorized worker callback.");
        }
        Optional<JobQueue> run = this.jobQueues.findById(dto.getJobQueueId());
        Optional<SourceJob> job = run.flatMap(r -> this.jobs.findByJobIdAndJobStatus(r.getJobId(), Status.Active));
        if (!job.isPresent() || job.get().getTaskDetail() == null) return new ResponseDto(ERROR, "This run has no task to run a step for.");
        AiPromptRun answer = this.steps.runForWorker(job.get().getTenantId(), job.get().getTaskDetail().getPipelineId(),
            dto.getJobQueueId(), dto.getStepTag(), dto.getItem(), dto.getPromptUuid(), dto.getVariables());
        return new ResponseDto("ok".equals(answer.getStatus()) ? SUCCESS : ERROR,
            "ok".equals(answer.getStatus()) ? String.format("Answered in %.1f s.", (answer.getLatencyMs() == null ? 0 : answer.getLatencyMs()) / 1000.0) : answer.getError(), answer);
    }

    /** What the model calls in a date range cost, per prompt -- the Reports page's AI section. */
    public ResponseDto usage(String from, String to) {
        LocalDate start = isNull(from) || from.isEmpty() ? LocalDate.now().minusDays(30) : LocalDate.parse(from);
        LocalDate end = isNull(to) || to.isEmpty() ? LocalDate.now() : LocalDate.parse(to);
        long scope = TenantContext.isPlatformAdmin() ? 0L : (TenantContext.getTenantId() == null ? -1L : TenantContext.getTenantId());
        List<AiUsageProjection> rows = scope == -1L ? Collections.emptyList()
            : this.runs.usageByPrompt(Timestamp.valueOf(start.atStartOfDay()), Timestamp.valueOf(end.plusDays(1).atStartOfDay()), scope);
        return new ResponseDto(SUCCESS, String.format("%d prompt(s) used.", rows.size()), rows);
    }

    public ResponseDto versionsOf(Long promptId) {
        Optional<AiPrompt> found = this.scopedFind(promptId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Prompt not found with %d.", promptId));
        return new ResponseDto(SUCCESS, "Versions.", this.versions.findAllByPromptIdOrderByVersionDesc(promptId));
    }

    private ResponseDto validate(AiPromptDto dto) {
        if (isNull(dto.getName()) || dto.getName().trim().isEmpty()) return new ResponseDto(ERROR, "Name missing.");
        if (isNull(dto.getUserTemplate()) || dto.getUserTemplate().trim().isEmpty()) return new ResponseDto(ERROR, "Message template missing.");
        List<AiPromptDto.Variable> vars = this.variablesOf(dto);
        Set<String> declared = vars.stream().map(v -> v.name).collect(Collectors.toSet());
        for (String used : PromptRunner.placeholders(dto.getUserTemplate())) {
            if (!declared.contains(used)) return new ResponseDto(ERROR, String.format("The template uses {{%s}} but no variable declares it.", used));
        }
        for (AiPromptDto.Variable v : vars) {
            if (isNull(v.name) || !v.name.matches("[A-Za-z_][A-Za-z0-9_]*")) return new ResponseDto(ERROR, "A variable name must be letters, digits and underscores, starting with a letter.");
        }
        if (dto.getTemperature() != null && (dto.getTemperature() < 0 || dto.getTemperature() > 2)) return new ResponseDto(ERROR, "Temperature is 0 to 2.");
        if (dto.getMaxTokens() != null && dto.getMaxTokens() < 1) return new ResponseDto(ERROR, "Max tokens must be at least 1.");
        if ("json".equals(dto.getOutputMode()) && !isNull(dto.getOutputSchema()) && !dto.getOutputSchema().trim().isEmpty()) {
            try { JsonParser.parseString(dto.getOutputSchema()).getAsJsonObject(); }
            catch (Exception ex) { return new ResponseDto(ERROR, "The output schema is not a JSON object."); }
        }
        if (TenantContext.isPlatformAdmin() && dto.getPromptId() == null && dto.getTenantId() == null) {
            return new ResponseDto(ERROR, "Pick the workspace this prompt belongs to.");
        }
        return null;
    }

    private List<AiPromptDto.Variable> variablesOf(AiPromptDto dto) {
        return dto.getVariables() == null ? new ArrayList<>() : dto.getVariables().stream().filter(v -> v != null && v.name != null && !v.name.trim().isEmpty())
            .peek(v -> { v.name = v.name.trim(); if (v.type == null) v.type = "text"; if (v.required == null) v.required = Boolean.TRUE; })
            .collect(Collectors.toList());
    }

    public List<AiPromptDto.Variable> parseVariables(String json) {
        if (isNull(json) || json.trim().isEmpty()) return new ArrayList<>();
        return this.gson.fromJson(json, new TypeToken<List<AiPromptDto.Variable>>() {}.getType());
    }

    public AiPromptDto toDto(AiPrompt p, Map<Long, String> tenantNames, boolean withRuns) {
        AiPromptDto d = new AiPromptDto();
        d.setPromptId(p.getPromptId()); d.setPromptUuid(p.getPromptUuid()); d.setTenantId(p.getTenantId()); d.setTenantName(tenantNames.get(p.getTenantId()));
        d.setName(p.getName()); d.setDescription(p.getDescription()); d.setConnectionId(p.getConnectionId()); d.setModel(p.getModel());
        d.setSystemInstructions(p.getSystemInstructions()); d.setUserTemplate(p.getUserTemplate()); d.setVariables(this.parseVariables(p.getVariables()));
        d.setOutputMode(p.getOutputMode()); d.setOutputSchema(p.getOutputSchema()); d.setTemperature(p.getTemperature()); d.setMaxTokens(p.getMaxTokens());
        d.setTags(p.getTags()); d.setVersion(p.getVersion()); d.setStatus(p.getStatus() == null ? null : p.getStatus().name());
        d.setDateCreated(p.getDateCreated()); d.setCreatedBy(p.getCreatedBy()); d.setCreatedByName(p.getCreatedByName()); d.setUpdatedByName(p.getUpdatedByName());
        Optional<AiModelConnection> c = p.getConnectionId() != null ? this.connections.scopedFind(p.getConnectionId()) : this.connections.defaultFor(p.getTenantId());
        if (c.isPresent()) {
            d.setConnectionName(p.getConnectionId() == null ? c.get().getName() + " (default)" : c.get().getName());
            d.setProvider(c.get().getProvider());
            d.setEffectiveModel(isNull(p.getModel()) ? c.get().getDefaultModel() : p.getModel());
        }
        if (withRuns) {
            d.setPipelineCount(this.pipelines.countUsingPrompt(p.getPromptId()));
            d.setRunCount(this.runs.countByPromptId(p.getPromptId()));
            this.runs.findFirstByPromptIdOrderByRunIdDesc(p.getPromptId()).ifPresent(r -> { d.setLastRunAt(r.getDateCreated()); d.setLastRunStatus(r.getStatus()); });
        }
        return d;
    }
}
