package process.customer;

import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Cursors;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.pipeline.StepStore;
import process.pipeline.items.ItemRow;
import process.pipeline.items.RunItems;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The customer API's run items (MIG-420; OpenAPI listRunItems, getRunItem). Scope runs:read for each.
 *
 * <ul>
 *   <li>GET /v1/runs/{runId}/items: the run's items -- one input file, recording, document or row of a per-row step each --
 *   in order, cursor paging, filtered by status (running, done, failed) and parentId (a document's pages). Each with its
 *   status, the step it failed at and why, its sub-item counts and the files its steps made for it, by file id
 *   (GET /v1/files/{fileId}).</li>
 *   <li>GET /v1/runs/{runId}/items/{itemKey}: one item, by its id ("2", a page "3.12") or by its key when the key has no
 *   '/', with each of its steps.</li>
 * </ul>
 * An item's data is in its files; the API never names a bucket, a key in a bucket or a storage key. A run that is not an item
 * run answers an empty list. A run of another workspace -- or one that does not exist -- is a 404, as GET /v1/runs/{id} is.
 */
@Service
public class CustomerItems {

    static final String NO_SUCH_ITEM = "No such item in this run.";

    private final CustomerRuns runs;
    private final RunItems items;
    private final StepStore steps;

    public CustomerItems(CustomerRuns runs, RunItems items, StepStore steps) {
        this.runs = runs;
        this.items = items;
        this.steps = steps;
    }

    /** Not read-only: a made file recorded before MIG-334 is given its file id the first time it is listed. */
    @Transactional
    public CustomerAnswer list(String runId, Integer limit, String cursor, String status, String parentId) {
        String instance = "/v1/runs/" + runId + "/items";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        int size;
        Long after;
        try {
            size = Cursors.limit(limit);
            after = Cursors.lastId(cursor);
        } catch (IllegalArgumentException bad) {
            return CustomerAnswer.problem(Problem.of(400, bad.getMessage()), instance);
        }
        String wanted = status == null || status.isEmpty() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (wanted != null && !"running".equals(wanted) && !"done".equals(wanted) && !"failed".equals(wanted)) {
            return CustomerAnswer.problem(Problem.of(400, "status is one of running, done, failed."), instance);
        }
        long jobQueueId = found.get().row.runId;
        List<ItemRow> all = this.items.currentOf(jobQueueId);
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        all.forEach(row -> byKey.put(row.itemKey, row));
        String parentKey = null;
        if (parentId != null && !parentId.isEmpty()) {
            ItemRow parent = byRef(all, parentId);
            if (parent == null) {
                return CustomerAnswer.problem(Problem.of(404, NO_SUCH_ITEM), instance);
            }
            parentKey = parent.itemKey;
        }
        final String level = parentKey;
        List<ItemRow> rows = all.stream().filter(row -> level == null ? row.parentKey == null : level.equals(row.parentKey))
            .filter(row -> wanted == null || matches(row, wanted))
            .filter(row -> after == null || row.position > after)
            .sorted((a, b) -> Integer.compare(a.position, b.position)).collect(Collectors.toList());
        boolean more = rows.size() > size;
        List<ItemRow> shown = more ? rows.subList(0, size) : rows;
        Function<ItemRow.StepEntry, String> names = this.items.stepNamer(jobQueueId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (ItemRow row : shown) {
            data.add(this.item(row, byKey, names, jobQueueId, false));
        }
        int total = 0;
        int done = 0;
        int failed = 0;
        int running = 0;
        for (ItemRow row : all) {
            if (row.parentKey != null) {
                continue;
            }
            total++;
            if (ItemRow.RUNNING.equals(row.status)) {
                running++;
            } else if (ItemRow.FAILED.equals(row.status) || row.childFailed > 0) {
                failed++;
            } else {
                done++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("items", total);
        summary.put("done", done);
        summary.put("failed", failed);
        summary.put("running", running);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(jobQueueId));
        body.put("summary", summary);
        body.put("data", data);
        body.put("nextCursor", more ? Cursors.after(shown.get(size - 1).position) : null);
        return CustomerAnswer.of(200, body, null);
    }

    @Transactional
    public CustomerAnswer get(String runId, String itemKey) {
        String instance = "/v1/runs/" + runId + "/items/" + itemKey;
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        long jobQueueId = found.get().row.runId;
        List<ItemRow> all = this.items.currentOf(jobQueueId);
        ItemRow row = byRef(all, itemKey);
        if (row == null) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_ITEM), instance);
        }
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        all.forEach(r -> byKey.put(r.itemKey, r));
        return CustomerAnswer.of(200, this.item(row, byKey, this.items.stepNamer(jobQueueId), jobQueueId, true), null);
    }

    /** The event relay's item (run.item.completed): the Item as GET /v1/runs/{id}/items/{itemKey} answers it, without its steps. */
    Map<String, Object> eventItem(ItemRow row, long jobQueueId) {
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        this.items.currentOf(jobQueueId).forEach(r -> byKey.put(r.itemKey, r));
        return this.item(row, byKey, this.items.stepNamer(jobQueueId), jobQueueId, false);
    }

    static boolean matches(ItemRow row, String status) {
        switch (status) {
            case "failed":
                return ItemRow.FAILED.equals(row.status) || row.childFailed > 0;
            case "done":
                return ItemRow.DONE.equals(row.status) && row.childFailed == 0;
            default:
                return ItemRow.RUNNING.equals(row.status);
        }
    }

    static ItemRow byRef(List<ItemRow> rows, String ref) {
        for (ItemRow row : rows) {
            if (row.itemRef.equals(ref)) {
                return row;
            }
        }
        for (ItemRow row : rows) {
            if (row.itemKey.equals(ref)) {
                return row;
            }
        }
        return null;
    }

    /** The Item schema. */
    Map<String, Object> item(ItemRow row, Map<String, ItemRow> byKey, Function<ItemRow.StepEntry, String> names, long jobQueueId,
        boolean withSteps) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.itemRef);
        item.put("key", row.itemKey);
        item.put("name", row.label);
        ItemRow parent = row.parentKey == null ? null : byKey.get(row.parentKey);
        item.put("parentId", parent == null ? null : parent.itemRef);
        item.put("status", row.status);
        item.put("failedStep", row.failedStepKey);
        item.put("failedStepName", row.failedStepKey == null ? null : this.items.stepName(jobQueueId, row.failedStepIndex, row.failedStepKey));
        item.put("reason", row.reason);
        item.put("attempt", row.attempt);
        item.put("startedAt", ApiTimes.utc(row.startedAt));
        item.put("endedAt", ApiTimes.utc(row.endedAt));
        item.put("durationMs", row.durationMs);
        if (row.childCount > 0) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("total", row.childCount);
            sub.put("done", row.childCount - row.childFailed);
            sub.put("failed", row.childFailed);
            item.put("subItems", sub);
        } else {
            item.put("subItems", null);
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (ItemRow.FileRow f : this.items.filesOf(row)) {
            if (f.runOutputId == null) {
                // An input object is the workspace's own file, named by no file id here.
                continue;
            }
            String fileId = f.fileId != null ? f.fileId : this.steps.fileIdOf(f.runOutputId);
            if (fileId == null) {
                continue;
            }
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("fileId", fileId);
            file.put("name", f.fileName);
            file.put("step", f.stepKey);
            file.put("format", f.format);
            file.put("bytes", f.byteCount);
            file.put("expiresAt", ApiTimes.utc(f.expiresAt));
            files.add(file);
        }
        item.put("files", files);
        if (withSteps) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (ItemRow.StepEntry entry : row.steps) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("key", entry.key);
                step.put("name", names.apply(entry));
                step.put("task", entry.task);
                step.put("status", entry.status);
                step.put("message", entry.note);
                step.put("rows", entry.n);
                step.put("files", entry.files == null ? 0 : entry.files);
                step.put("sharedBy", entry.sharedBy);
                step.put("durationMs", entry.ms);
                step.put("reusedFromAttempt", entry.reusedFrom);
                steps.add(step);
            }
            item.put("steps", steps);
        }
        return item;
    }
}
