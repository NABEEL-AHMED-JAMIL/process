package process.pipeline.items;

import process.security.TenantContext;
import org.springframework.stereotype.Service;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.RunOwnership;
import process.pipeline.RunRetries;
import process.pipeline.StepTasks;
import process.pipeline.registry.ItemRole;
import process.security.ManagedSessionOnly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Retry failed items (MIG-420): some of a run's items -- the ones named, or every failed one -- run again as the run's next
 * attempt, and only they. "item": the item's steps again from the one after the step that found it (a page: from the step that
 * split it); "failed_step": from the earliest step the items failed at. Each retried item gets a new version; the earlier one
 * is kept, shown as earlier. Only their calls are made, and billed, again: every per-row call is keyed by its item
 * (ItemTracker.tagOf), so an AI answer that was fine is reused, never re-bought. The rules are a resume's (RunRetries): the run's
 * owner or an administrator, a finished run, no other run of the pipeline in flight, the kept outputs before the step not
 * expired, the input files unchanged.
 */
@Service
public class ItemRetries {

    public static final String WHOLE = "item";
    public static final String FAILED_STEP = "failed_step";
    static final int MAX_ITEMS = ItemTracker.MAX_ITEMS;

    private final RunItemStore store;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final RunRetries retries;
    private final RunItems items;
    private final StepTasks tasks;

    public ItemRetries(RunItemStore store, JobQueueRepository runs, SourceJobRepository jobs, RunRetries retries, RunItems items, StepTasks tasks) {
        this.store = store;
        this.runs = runs;
        this.jobs = jobs;
        this.retries = retries;
        this.items = items;
        this.tasks = tasks;
    }

    /** Body of the console's retry: the run, the items (by ref; none with failed = every failed one), and where to start. */
    public static class Request {
        public Long jobQueueId;
        public List<String> refs;
        public Boolean failed;
        public String from;

        public Long getJobQueueId() { return jobQueueId; }

        public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

        public List<String> getRefs() { return refs; }

        public void setRefs(List<String> refs) { this.refs = refs; }

        public Boolean getFailed() { return failed; }

        public void setFailed(Boolean failed) { this.failed = failed; }

        public String getFrom() { return from; }

        public void setFrom(String from) { this.from = from; }
    }

    public RunItems.Answer retry(Request request, Long userId) {
        if (ManagedSessionOnly.isPlatformOwnSignIn()) {
            return RunItems.Answer.refused(403, ManagedSessionOnly.REFUSAL);
        }
        Optional<RunOwnership.Owned> owned = request == null || request.jobQueueId == null ? Optional.empty()
            : RunOwnership.owned(this.runs, this.jobs, request.jobQueueId);
        if (!owned.isPresent()) {
            return RunItems.Answer.refused(404, RunItems.RUN_NOT_FOUND);
        }
        String mode = FAILED_STEP.equals(request.from) ? FAILED_STEP : WHOLE;
        List<ItemRow> all = this.store.current(request.jobQueueId);
        if (all.isEmpty()) {
            return RunItems.Answer.refused(409, "This run has no items: retry the run instead.");
        }
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        all.forEach(row -> byKey.put(row.itemKey, row));
        List<ItemRow> chosen = new ArrayList<>();
        boolean positional = all.stream().flatMap(row -> row.steps.stream()).anyMatch(e -> "#".equals(e.idc));
        if (positional) {
            return RunItems.Answer.refused(409, "This run's items have no key column (they are its rows by number), so they cannot be "
                + "retried one by one; retry the run instead.");
        }
        if (request.refs != null && !request.refs.isEmpty()) {
            for (String ref : new LinkedHashSet<>(request.refs)) {
                ItemRow row = RunItems.byRef(all, ref == null ? "" : ref.trim());
                if (row == null) {
                    return RunItems.Answer.refused(404, RunItems.ITEM_NOT_FOUND);
                }
                if (!ItemRow.FAILED.equals(row.status) && row.childFailed == 0) {
                    return RunItems.Answer.refused(409, String.format("%s did not fail: only failed items are retried.", row.label));
                }
                if (row.childFailed > 0 && !ItemRow.FAILED.equals(row.status)) {
                    // A document with failed pages: its failed pages.
                    for (ItemRow child : all) {
                        if (row.itemKey.equals(child.parentKey) && ItemRow.FAILED.equals(child.status)) {
                            chosen.add(child);
                        }
                    }
                } else {
                    chosen.add(row);
                }
            }
        } else if (Boolean.TRUE.equals(request.failed)) {
            for (ItemRow row : all) {
                boolean parentFailed = row.parentKey != null && byKey.containsKey(row.parentKey) && ItemRow.FAILED.equals(byKey.get(row.parentKey).status);
                if (ItemRow.FAILED.equals(row.status) && !parentFailed) {
                    chosen.add(row);
                }
            }
        }
        if (chosen.isEmpty()) {
            return RunItems.Answer.refused(409, "No failed item to retry.");
        }
        if (chosen.size() > MAX_ITEMS) {
            return RunItems.Answer.refused(409, String.format("At most %,d items are retried at once.", MAX_ITEMS));
        }
        List<ItemTracker.StepInfo> plan = ItemTracker.plan(this.definitionOf(request.jobQueueId), this.tasks);
        int source = ItemTracker.sourceOf(plan);
        if (source < 0) {
            return RunItems.Answer.refused(409, "This run's pipeline no longer works item by item; retry the run instead.");
        }
        int from = Integer.MAX_VALUE;
        for (ItemRow row : chosen) {
            int at;
            if (WHOLE.equals(mode)) {
                at = row.parentKey != null ? this.fanOutOf(plan) : plan.get(source).role == ItemRole.SOURCE ? source + 1 : source;
                if (row.failedStepIndex != null && row.failedStepIndex < at) {
                    at = row.failedStepIndex;
                }
            } else {
                at = row.failedStepIndex == null ? source : row.failedStepIndex;
            }
            from = Math.min(from, at);
        }
        from = Math.max(0, Math.min(from, plan.size() - 1));
        List<String> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ItemRow row : chosen) {
            if (seen.add(row.itemKey)) {
                keys.add(row.itemKey);
            }
        }
        boolean admin = "TENANT_ADMIN".equals(TenantContext.getUserRole());
        RunRetries.Outcome outcome = this.retries.retryItems(owned.get().run, owned.get().job, from, keys, mode, RunRetries.Actor.person(userId, admin));
        if (outcome.refusal != null) {
            return RunItems.Answer.refused(409, outcome.refusal);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobQueueId", request.jobQueueId);
        data.put("attempt", outcome.plan.nextAttempt);
        data.put("items", keys.size());
        data.put("from", mode);
        data.put("fromStep", outcome.plan.fromName);
        data.put("fromStepIndex", outcome.plan.fromIndex);
        data.put("reused", outcome.plan.reused.size());
        return RunItems.Answer.ok(String.format("Retrying %d item%s from %s: attempt %d.", keys.size(), keys.size() == 1 ? "" : "s",
            outcome.plan.fromName, outcome.plan.nextAttempt), data);
    }

    private int fanOutOf(List<ItemTracker.StepInfo> plan) {
        for (ItemTracker.StepInfo step : plan) {
            if (step.role == ItemRole.FAN_OUT) {
                return step.index;
            }
        }
        return ItemTracker.sourceOf(plan);
    }

    private process.pipeline.PipelineDefinition definitionOf(long jobQueueId) {
        return this.items.definitionOf(jobQueueId);
    }
}
