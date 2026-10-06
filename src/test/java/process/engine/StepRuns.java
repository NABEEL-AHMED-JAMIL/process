package process.engine;

import process.ai.AiStepService;

import java.util.Objects;

import static org.mockito.ArgumentMatchers.argThat;

/** Matches the run PreDispatchPhase hands AiStepService.apply: its workspace, pipeline and (when named) queue row. */
final class StepRuns {

    private StepRuns() {}

    static AiStepService.Run of(Long tenantId, String pipelineId, Long jobQueueId) {
        return argThat(run -> run != null && Objects.equals(run.tenantId, tenantId) && Objects.equals(run.pipelineId, pipelineId)
            && (jobQueueId == null || Objects.equals(run.jobQueueId, jobQueueId)));
    }

    static AiStepService.Run ofTenant(Long tenantId) {
        return argThat(run -> run != null && Objects.equals(run.tenantId, tenantId));
    }
}
