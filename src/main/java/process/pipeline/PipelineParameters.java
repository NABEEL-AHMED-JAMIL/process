package process.pipeline;

import org.springframework.stereotype.Component;
import process.model.pojo.SourceJob;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

/**
 * MIG-347: a schedule's run parameters, for the ones who start its runs -- Run now, "Run with...", a backfill, the API:
 * what its pipeline declares (its latest stored definition, the one a run started now follows), and a run's given values
 * checked against them, ready for job_queue.run_params.
 */
@Component
public class PipelineParameters {

    private final PipelineDefinitionStore definitions;

    public PipelineParameters(PipelineDefinitionStore definitions) {
        this.definitions = definitions;
    }

    /** The job's pipeline's declared parameters; empty for a job without a step pipeline or one that declares none. */
    public List<PipelineDefinition.Parameter> of(SourceJob job) {
        if (job == null || job.getTenantId() == null || job.getTaskDetail() == null || job.getTaskDetail().getPipelineId() == null) {
            return Collections.emptyList();
        }
        Optional<PipelineDefinitionStore.Stored> stored = this.definitions.latestFor(job.getTenantId(), job.getTaskDetail().getPipelineId());
        if (!stored.isPresent()) {
            return Collections.emptyList();
        }
        try {
            PipelineDefinition definition = stored.get().definition();
            return definition.isLegacy() ? Collections.<PipelineDefinition.Parameter>emptyList() : definition.effectiveParameters();
        } catch (IllegalStateException unreadable) {
            return Collections.emptyList();
        }
    }

    /**
     * V220: whether the job runs a step pipeline (Core's engine), whose runs keep their data under their own run id -- so a
     * workflow's runs of it may run side by side. A legacy one (external workers) shares the task's one output folder.
     */
    public boolean runsSteps(SourceJob job) {
        if (job == null || job.getTenantId() == null || job.getTaskDetail() == null || job.getTaskDetail().getPipelineId() == null) {
            return false;
        }
        Optional<PipelineDefinitionStore.Stored> stored = this.definitions.latestFor(job.getTenantId(), job.getTaskDetail().getPipelineId());
        try {
            return stored.isPresent() && !stored.get().definition().isLegacy();
        } catch (IllegalStateException unreadable) {
            return false;
        }
    }

    /** Given values checked for a run of this job: its run_params (null when the pipeline has none), or the refusal. */
    public static final class Checked {
        public final String runParams;
        public final RunParameters.Resolved resolved;
        public final String refusal;

        Checked(String runParams, RunParameters.Resolved resolved, String refusal) {
            this.runParams = runParams;
            this.resolved = resolved;
            this.refusal = refusal;
        }
    }

    public Checked check(SourceJob job, Map<String, ?> given) {
        return check(this.of(job), given);
    }

    /** As {@link #check(SourceJob, Map)} for parameters already read. */
    public static Checked check(List<PipelineDefinition.Parameter> declared, Map<String, ?> given) {
        RunParameters.Resolved resolved = RunParameters.resolve(declared, given);
        if (!resolved.ok()) {
            return new Checked(null, resolved, "The run's parameters do not stand: " + resolved.sentence());
        }
        if (given == null || given.isEmpty()) {
            // Nothing given: the run takes the defaults, read when it starts (the version it follows then).
            return new Checked(null, resolved, null);
        }
        // Only what was given is kept: a default not overridden is read from the version the run follows.
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : given.keySet()) {
            if (resolved.values.containsKey(name)) {
                kept.put(name, resolved.values.get(name));
            }
        }
        return new Checked(kept.isEmpty() ? null : new RunParameters.RunValues(kept, null, null, null, null).toJson(), resolved, null);
    }
}
