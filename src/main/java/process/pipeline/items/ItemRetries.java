package process.pipeline.items;

import java.util.HashSet;
import process.pipeline.PipelineDefinition;
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
 * split it); "failed_step": each from the step it failed at -- the attempt resumes at the earliest of them, and an item that had
 * passed a per-item step before its own failed step reuses its earlier output there (ZT-I11). Each retried item gets a new
 * version; the earlier one is kept, shown as earlier. Only their calls are made, and billed, again: every per-row call is keyed
 * by its item (ItemTracker.tagOf), so an AI answer that was fine is reused, never re-bought. The rules are a resume's (RunRetries): the run's
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
        /**
         * MIG-421 (bulk select): retry only the failed ones among the items named -- the refs ticked, or with {@code all} every
         * item the status and search find, less {@code except} -- instead of refusing the ones that did not fail.
         */
        public Boolean onlyFailed;
        public Boolean all;
        public String status;
        public String search;
        public List<String> except;

        public Boolean getOnlyFailed() { return onlyFailed; }

        public void setOnlyFailed(Boolean onlyFailed) { this.onlyFailed = onlyFailed; }

        public Boolean getAll() { return all; }

        public void setAll(Boolean all) { this.all = all; }

        public String getStatus() { return status; }

        public void setStatus(String status) { this.status = status; }

        public String getSearch() { return search; }

        public void setSearch(String search) { this.search = search; }

        public List<String> getExcept() { return except; }

        public void setExcept(List<String> except) { this.except = except; }

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
        List<String> named = request.refs;
        if (Boolean.TRUE.equals(request.all) || Boolean.TRUE.equals(request.onlyFailed)) {
            // MIG-421: a bulk selection -- what the Items table's filter and search find (all pages), or the refs ticked -- and of
            // it only the failed items ("Retry N failed").
            RunItems.ListRequest selection = new RunItems.ListRequest();
            selection.jobQueueId = request.jobQueueId;
            selection.refs = Boolean.TRUE.equals(request.all) ? null : request.refs;
            selection.except = request.except;
            selection.status = request.status;
            selection.search = request.search;
            RunItems.Selected selected = this.items.selected(selection);
            if (selected == null) {
                return RunItems.Answer.refused(404, RunItems.RUN_NOT_FOUND);
            }
            if (selection.refs != null && selected.items.size() < new LinkedHashSet<>(selection.refs).size()) {
                return RunItems.Answer.refused(404, RunItems.ITEM_NOT_FOUND);
            }
            named = new ArrayList<>();
            for (ItemRow row : selected.items) {
                if (ItemRow.FAILED.equals(row.status) || row.childFailed > 0) {
                    named.add(row.itemRef);
                }
            }
            if (named.isEmpty()) {
                return RunItems.Answer.refused(409, "None of the selected items failed: there is nothing to retry.");
            }
        }
        if (named != null && !named.isEmpty()) {
            for (String ref : new LinkedHashSet<>(named)) {
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
        Set<Integer> failedAt = new HashSet<>();
        for (ItemRow row : chosen) {
            failedAt.add(row.failedStepIndex == null ? source : row.failedStepIndex);
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
        // ZT-I11: items that failed at different steps each run again from their own (an earlier step they passed is reused).
        String fromWhere = !WHOLE.equals(mode) && failedAt.size() > 1
            ? String.format("their failed steps (the first at %s)", outcome.plan.fromName) : outcome.plan.fromName;
        return RunItems.Answer.ok(String.format("Retrying %d item%s from %s: attempt %d.", keys.size(), keys.size() == 1 ? "" : "s",
            fromWhere, outcome.plan.nextAttempt), data);
    }

    private int fanOutOf(List<ItemTracker.StepInfo> plan) {
        for (ItemTracker.StepInfo step : plan) {
            if (step.role == ItemRole.FAN_OUT) {
                return step.index;
            }
        }
        return ItemTracker.sourceOf(plan);
    }

    private PipelineDefinition definitionOf(long jobQueueId) {
        return this.items.definitionOf(jobQueueId);
    }
}
