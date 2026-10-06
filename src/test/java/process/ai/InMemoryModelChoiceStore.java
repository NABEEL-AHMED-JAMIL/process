package process.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link ModelChoiceStore} in memory, for tests that do not need Postgres: what JdbcModelChoiceStore does, as maps. */
public final class InMemoryModelChoiceStore implements ModelChoiceStore {

    /** job id to tenant id: the jobs that exist, in their workspaces. */
    public final Map<Long, Long> jobs = new HashMap<>();

    public final Map<Long, String> schedules = new HashMap<>();

    public final List<RunAiStep> steps = new ArrayList<>();

    @Override
    public int saveScheduleProfiles(Long jobId, Long tenantId, String profiles, Long updatedBy) {
        if (!tenantId.equals(this.jobs.get(jobId))) {
            return 0;
        }
        this.schedules.put(jobId, profiles);
        return 1;
    }

    @Override
    public String scheduleProfiles(Long jobId, Long tenantId) {
        return tenantId.equals(this.jobs.get(jobId)) ? this.schedules.get(jobId) : null;
    }

    @Override
    public void recordSteps(List<RunAiStep> recorded) {
        for (RunAiStep s : recorded) {
            this.steps.removeIf(old -> old.jobQueueId.equals(s.jobQueueId) && old.attempt.equals(s.attempt) && old.stepKey.equals(s.stepKey));
            this.steps.add(s);
        }
    }

    @Override
    public List<RunAiStep> stepsOfRun(Long jobQueueId) {
        return this.steps.stream().filter(s -> s.jobQueueId.equals(jobQueueId)).collect(Collectors.toList());
    }
}
