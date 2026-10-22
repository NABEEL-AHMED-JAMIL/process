package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.RunFacets;
import process.pipeline.DatasetStore;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunOwnership;
import process.pipeline.StepStore;
import process.pipeline.data.RowSource;
import process.security.JobOwnership;
import process.security.ManagedSessionOnly;
import process.security.TenantContext;
import process.security.TenantOwnership;
import process.util.BusinessTime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A schedule's items across its runs (MIG-421, the Schedule's Items tab): every run that processed an item -- found by its
 * key or name, or by a value ("risk = High") -- newest first, with how the run started, the pipeline version it ran, the
 * item's status, the fields asked for and its report; and two runs of one item compared step by step.
 *
 * <ul>
 *   <li><b>From the item records.</b> The list reads run_item (its key, name and kept values, V234) and run_item_report,
 *       never the runs' outputs; only the two items a compare names are read from their steps' kept rows, by pointer.</li>
 *   <li><b>Who.</b> A schedule the caller cannot see -- another workspace's, a deleted one, one a restricted user is not on --
 *       is "not found" (404), and of the rest each run is listed only if the caller reads it (RunReaders). A platform
 *       administrator's own sign-in reads none of it; a managed session's compare is audited at the controller.</li>
 *   <li><b>Masking.</b> Every value -- the fields, both sides of a compare -- is the person's masked view, and a search
 *       matches only what the person would see: a masked value is never found by its real text.</li>
 * </ul>
 */
@Service
public class ItemsAcrossRuns {

    static final String SCHEDULE_NOT_FOUND = "Schedule not found.";
    /** The most item rows one search considers (newest first); the answer says when there were more. */
    static final int CAP = 2_000;
    static final int MAX_CHANGES = 200;
    static final int MAX_VALUE = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunItems items;
    private final RunItemStore store;
    private final SourceJobRepository jobs;
    private final StepStore steps;
    private final PipelineDefinitionStore definitions;
    private final DatasetStore datasets;

    public ItemsAcrossRuns(RunItems items, RunItemStore store, SourceJobRepository jobs, StepStore steps, PipelineDefinitionStore definitions,
        DatasetStore datasets) {
        this.items = items;
        this.store = store;
        this.jobs = jobs;
        this.steps = steps;
        this.definitions = definitions;
        this.datasets = datasets;
    }

    /** The list's body: the schedule, the search (or an exact key), the status, the window, the page, the fields to show. */
    public static class Request {
        public Long jobId;
        public String search;
        public String key;
        public String status;
        public String from;
        public String to;
        public Integer page;
        public Integer size;
        public List<String> fields;

        public Long getJobId() { return jobId; }

        public void setJobId(Long jobId) { this.jobId = jobId; }

        public String getSearch() { return search; }

        public void setSearch(String search) { this.search = search; }

        public String getKey() { return key; }

        public void setKey(String key) { this.key = key; }

        public String getStatus() { return status; }

        public void setStatus(String status) { this.status = status; }

        public String getFrom() { return from; }

        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }

        public void setTo(String to) { this.to = to; }

        public Integer getPage() { return page; }

        public void setPage(Integer page) { this.page = page; }

        public Integer getSize() { return size; }

        public void setSize(Integer size) { this.size = size; }

        public List<String> getFields() { return fields; }

        public void setFields(List<String> fields) { this.fields = fields; }
    }

    // ------------------------------------------------------------------------------------------------------------ access

    /** The schedule, when the caller may see it; empty -- "not found" -- otherwise. */
    Optional<SourceJob> schedule(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        Optional<SourceJob> job = this.jobs.findById(jobId);
        if (!job.isPresent() || !TenantOwnership.isOwnedByCaller(job.get().getTenantId()) || Status.Delete.equals(job.get().getJobStatus())) {
            return Optional.empty();
        }
        return job;
    }

    /** Whether the caller sees every run of the schedule (an administrator, its owner), or only the ones they review. */
    private static boolean seesAll(SourceJob job) {
        return "TENANT_ADMIN".equals(TenantContext.getUserRole()) || JobOwnership.isVisibleToCaller(job);
    }

    private static RunItems.Answer platformRefusal() {
        return ManagedSessionOnly.isPlatformOwnSignIn() ? RunItems.Answer.refused(403, ManagedSessionOnly.REFUSAL) : null;
    }

    // ------------------------------------------------------------------------------------------------------------- list

    public RunItems.Answer list(Request request) {
        RunItems.Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<SourceJob> found = this.schedule(request == null ? null : request.jobId);
        if (!found.isPresent()) {
            return RunItems.Answer.refused(404, SCHEDULE_NOT_FOUND);
        }
        SourceJob job = found.get();
        boolean all = seesAll(job);
        RunItemStore.AcrossQuery query = new RunItemStore.AcrossQuery();
        RunItems.Search search = null;
        if (request.key != null && !request.key.trim().isEmpty()) {
            query.key = request.key.trim();
        } else {
            search = RunItems.Search.of(request.search);
            if (search != null && search.field != null) {
                query.field = search.field;
                query.value = search.value;
            } else if (search != null) {
                query.text = search.text;
            }
        }
        query.status = request.status == null || "all".equalsIgnoreCase(request.status.trim()) ? null : request.status.trim().toLowerCase(Locale.ROOT);
        query.from = instant(request.from);
        query.to = instant(request.to);
        List<RunItemStore.AcrossRow> candidates = this.store.acrossRuns(job.getJobId(), query, CAP + 1);
        boolean capped = candidates.size() > CAP;
        if (capped) {
            candidates = candidates.subList(0, CAP);
        }
        // Each run once: readable by the caller (an administrator or the owner reads every run of their schedule), and its masks.
        Map<Long, Boolean> readable = new HashMap<>();
        Map<Long, ItemMasks> masks = new HashMap<>();
        Map<Long, RunOwnership.Owned> owners = new HashMap<>();
        List<RunItemStore.AcrossRow> verified = new ArrayList<>();
        for (RunItemStore.AcrossRow row : candidates) {
            long run = row.item.jobQueueId;
            boolean may = readable.computeIfAbsent(run, id -> {
                Optional<RunOwnership.Owned> owned = this.items.readable(id);
                owned.ifPresent(o -> owners.put(id, o));
                return owned.isPresent();
            });
            if (!may) {
                continue;
            }
            ItemMasks m = masks.computeIfAbsent(run, id -> this.items.masksFor(owners.get(id).job, this.items.definitionOf(id)));
            if (search != null && !matches(search, row.item, m)) {
                continue;
            }
            verified.add(row);
        }
        int size = Math.max(1, Math.min(RunItems.MAX_PAGE, request.size == null ? 25 : request.size));
        int page = Math.max(0, request.page == null ? 0 : request.page);
        int from = Math.min(verified.size(), page * size);
        List<RunItemStore.AcrossRow> shown = verified.subList(from, Math.min(verified.size(), from + size));
        this.fill(shown);
        // The fields a person may add as columns: the values the matching items keep.
        List<String> available = new ArrayList<>();
        for (RunItemStore.AcrossRow row : verified) {
            if (row.item.fields == null) {
                continue;
            }
            for (String name : row.item.fields.keySet()) {
                if (!available.contains(name) && available.size() < ItemFields.MAX_FIELDS) {
                    available.add(name);
                }
            }
        }
        List<String> wanted = request.fields == null ? Collections.<String>emptyList()
            : request.fields.stream().filter(available::contains).limit(ItemFields.MAX_FIELDS).collect(Collectors.toList());
        Map<String, RunItemStore.KeptReport> reports = new HashMap<>();
        if (!shown.isEmpty()) {
            for (RunItemStore.KeptReport r : this.store.reportsOf(shown.stream().map(s -> s.item.jobQueueId).collect(Collectors.toSet()),
                shown.stream().map(s -> s.item.itemKey).collect(Collectors.toSet()))) {
                reports.putIfAbsent(r.jobQueueId + "|" + r.itemKey, r);
            }
        }
        Map<Long, Integer> versions = new HashMap<>();
        Map<Long, RunItems.Steps> plans = new HashMap<>();
        Set<String> masked = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunItemStore.AcrossRow row : shown) {
            long run = row.item.jobQueueId;
            ItemMasks m = masks.get(run);
            RunItems.Steps plan = plans.computeIfAbsent(run, this.items::stepsOfRun);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("jobQueueId", run);
            view.put("ref", row.item.itemRef);
            view.put("key", row.item.itemKey);
            view.put("label", row.item.label);
            view.put("startedAt", row.runStarted == null ? null : row.runStarted.toString());
            view.put("endedAt", row.item.endedAt == null ? null : row.item.endedAt.toString());
            view.put("trigger", row.trigger);
            view.put("triggerLabel", triggerLabel(row));
            view.put("runAttempt", row.runAttempt);
            view.put("runStatus", row.runStatus);
            Integer version = row.item.pipelineVersion != null ? row.item.pipelineVersion : versions.computeIfAbsent(run, this::versionOf);
            view.put("pipelineVersion", version);
            view.put("status", row.item.status);
            view.put("flag", row.item.flag);
            view.put("attempt", row.item.attempt);
            Map<String, Object> failedStep = null;
            if (row.item.failedStepIndex != null) {
                failedStep = new LinkedHashMap<>();
                failedStep.put("index", row.item.failedStepIndex);
                failedStep.put("key", row.item.failedStepKey);
                failedStep.put("name", plan.name(row.item.failedStepIndex, row.item.failedStepKey));
            }
            view.put("failedStep", failedStep);
            Map<String, Object> children = null;
            if (row.item.childCount > 0) {
                children = new LinkedHashMap<>();
                children.put("total", row.item.childCount);
                children.put("failed", row.item.childFailed);
            }
            view.put("children", children);
            view.put("durationMs", row.item.durationMs);
            Map<String, Object> values = new LinkedHashMap<>();
            if (!wanted.isEmpty() && row.item.fields != null) {
                Map<String, Object> shownValues = maskedFields(row.item.fields, m, masked);
                for (String name : wanted) {
                    values.put(name, shownValues.get(name));
                }
            }
            view.put("fields", values);
            RunItemStore.KeptReport report = reports.get(run + "|" + row.item.itemKey);
            if (report != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("label", report.reportLabel != null ? report.reportLabel : report.fileName);
                r.put("version", report.reportVersion > 0 ? report.reportVersion : null);
                r.put("fileName", report.fileName);
                r.put("fileRef", report.fileRef);
                r.put("madeAttempt", report.madeAttempt);
                view.put("report", r);
            } else {
                view.put("report", null);
            }
            out.add(view);
        }
        // The keys found, for "call-a.m4a processed 4 times" (or a choice when a search finds several).
        Map<String, int[]> keyCounts = new LinkedHashMap<>();
        Map<String, String> keyLabels = new HashMap<>();
        for (RunItemStore.AcrossRow row : verified) {
            keyCounts.computeIfAbsent(row.item.itemKey, k -> new int[1])[0]++;
            keyLabels.putIfAbsent(row.item.itemKey, row.item.label);
        }
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Map.Entry<String, int[]> e : keyCounts.entrySet()) {
            if (keys.size() >= 20) {
                break;
            }
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("key", e.getKey());
            k.put("label", keyLabels.get(e.getKey()));
            k.put("runs", e.getValue()[0]);
            keys.add(k);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getJobId());
        data.put("jobName", job.getJobName());
        data.put("total", verified.size());
        data.put("capped", capped);
        data.put("page", page);
        data.put("size", size);
        data.put("keys", keys);
        data.put("distinctKeys", keyCounts.size());
        data.put("fields", available);
        data.put("masked", new ArrayList<>(masked));
        data.put("items", out);
        if (!all) {
            data.put("note", "Only the runs you review are listed.");
        }
        return RunItems.Answer.ok(String.format("%d run(s) of %d item(s).", verified.size(), keyCounts.size()), data);
    }

    /** Whether the item answers the search as the person sees it: its name or key, or its masked values. */
    static boolean matches(RunItems.Search search, ItemRow row, ItemMasks masks) {
        Map<String, Object> shown = row.fields == null ? Collections.<String, Object>emptyMap() : maskedFields(row.fields, masks, new LinkedHashSet<>());
        if (search.text != null) {
            if (row.label.toLowerCase(Locale.ROOT).contains(search.text) || row.itemKey.toLowerCase(Locale.ROOT).contains(search.text)) {
                return true;
            }
            for (Object v : shown.values()) {
                if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains(search.text)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, Object> e : shown.entrySet()) {
            if (ItemFields.names(e.getKey(), search.field) && e.getValue() != null
                && String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT).equals(search.value)) {
                return true;
            }
        }
        return false;
    }

    /** The values as the person may see them; the masked names added to {@code masked}. */
    static Map<String, Object> maskedFields(Map<String, Object> fields, ItemMasks masks, Set<String> masked) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> one = masks.rows(Collections.singletonList(fields), fields.keySet(), masked);
        return one.isEmpty() ? Collections.<String, Object>emptyMap() : one.get(0);
    }

    /** "by schedule", "Run now", "Run with…", "File arrived"...: how the run started, its retries said apart. */
    static String triggerLabel(RunItemStore.AcrossRow row) {
        String label = RunFacets.MANUAL.equals(row.trigger) && row.withValues ? "Run with…" : RunFacets.TRIGGERS.get(row.trigger);
        if (label == null) {
            label = row.trigger;
        }
        return row.runAttempt > 1 ? String.format("%s · retried (attempt %d)", label, row.runAttempt) : label;
    }

    /** MIG-421: an older item (before V234) keeps no values yet: those shown are read now, once, by pointer, and kept. */
    private void fill(List<RunItemStore.AcrossRow> shown) {
        List<ItemRow> missing = new ArrayList<>();
        for (RunItemStore.AcrossRow row : shown) {
            if (row.item.fields == null && row.item.finished()) {
                missing.add(row.item);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<ItemRow> read = ItemFields.read(missing, new ItemFields.Opener() {
            @Override
            public String storageKeyOf(long runDatasetId) {
                Optional<StepStore.DatasetFile> file = ItemsAcrossRuns.this.steps.datasetById(runDatasetId);
                return file.isPresent() && (file.get().expiresAt == null || file.get().expiresAt.isAfter(Instant.now())) ? file.get().storageKey : null;
            }

            @Override
            public RowSource open(String storageKey) throws Exception {
                return ItemsAcrossRuns.this.datasets.open(storageKey, 500);
            }
        });
        if (!read.isEmpty()) {
            try {
                this.store.saveFields(read);
            } catch (RuntimeException unsaved) {
                // Shown now, read again next time.
            }
        }
    }

    /** The pipeline version a run ran (its pinned definition), for an item row from before V234. */
    private Integer versionOf(long jobQueueId) {
        try {
            return this.steps.pinnedDefinition(jobQueueId).flatMap(this.definitions::byId).map(s -> s.version).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    static Instant instant(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- compare

    /**
     * Two runs of one item of the schedule compared: each step's data for the item, as the person sees it, field by field
     * (what was, what is), and what changed in the pipeline between the versions they ran. {@code a} is shown as the newer.
     */
    public RunItems.Answer compare(Long jobId, String key, Long a, Long b) {
        RunItems.Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<SourceJob> found = this.schedule(jobId);
        if (!found.isPresent()) {
            return RunItems.Answer.refused(404, SCHEDULE_NOT_FOUND);
        }
        if (key == null || key.trim().isEmpty() || a == null || b == null) {
            return RunItems.Answer.refused(400, "Name the item (key) and the two runs (a, b).");
        }
        if (a.equals(b)) {
            return RunItems.Answer.refused(400, "Pick two different runs to compare.");
        }
        Side newer = this.side(found.get(), a, key.trim());
        Side older = this.side(found.get(), b, key.trim());
        if (newer == null || older == null) {
            return RunItems.Answer.refused(404, "That item is not in both runs.");
        }
        if (newer.started != null && older.started != null && newer.started.isBefore(older.started)) {
            Side swap = newer;
            newer = older;
            older = swap;
        }
        Set<String> masked = new LinkedHashSet<>();
        List<Map<String, Object>> stepViews = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        newer.row.steps.forEach(e -> keys.add(e.key));
        older.row.steps.forEach(e -> keys.add(e.key));
        int changed = 0;
        RunItems.Rows rows = new RunItems.Rows(this.datasets, this.steps);
        for (String stepKey : keys) {
            ItemRow.StepEntry now = entry(newer.row, stepKey);
            ItemRow.StepEntry was = entry(older.row, stepKey);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", stepKey);
            s.put("name", now != null ? newer.steps.name(now.i, now.key) : older.steps.name(was.i, was.key));
            s.put("task", now != null ? now.task : was.task);
            s.put("a", sideView(newer, now));
            s.put("b", sideView(older, was));
            JsonNode after = this.dataOf(rows, now, newer.masks, masked);
            JsonNode before = this.dataOf(rows, was, older.masks, masked);
            List<Map<String, Object>> changes = new ArrayList<>();
            boolean truncated = diff("", before, after, changes);
            s.put("same", changes.isEmpty() && before != null && after != null);
            s.put("changes", changes);
            s.put("truncated", truncated);
            if (before == null || after == null) {
                s.put("note", before == null && after == null ? "Neither run kept data for this step."
                    : before == null ? "Only the newer run has data for this step." : "Only the older run has data for this step.");
            }
            changed += changes.size();
            stepViews.add(s);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", found.get().getJobId());
        data.put("key", newer.row.itemKey);
        data.put("label", newer.row.label);
        data.put("a", runView(newer));
        data.put("b", runView(older));
        data.put("pipeline", pipelineChange(older, newer));
        data.put("steps", stepViews);
        data.put("changed", changed);
        data.put("masked", new ArrayList<>(masked));
        return RunItems.Answer.ok(String.format("%d field(s) changed.", changed), data);
    }

    /** One run's side of a compare: its item, its steps, the definition it ran, the person's masks for it. */
    static final class Side {
        long jobQueueId;
        ItemRow row;
        RunItems.Steps steps;
        PipelineDefinition definition;
        Integer version;
        ItemMasks masks;
        Instant started;
        String status;
    }

    private Side side(SourceJob job, long jobQueueId, String key) {
        Optional<RunOwnership.Owned> owned = this.items.readable(jobQueueId);
        if (!owned.isPresent() || !Objects.equals(owned.get().run.getJobId(), job.getJobId())) {
            return null;
        }
        ItemRow current = null;
        for (ItemRow row : this.store.versions(jobQueueId, key)) {
            if (row.current) {
                current = row;
            }
        }
        if (current == null) {
            return null;
        }
        Side side = new Side();
        side.jobQueueId = jobQueueId;
        side.row = current;
        side.steps = this.items.stepsOfRun(jobQueueId);
        side.definition = side.steps.definition;
        side.version = current.pipelineVersion != null ? current.pipelineVersion : this.versionOf(jobQueueId);
        side.masks = this.items.masksFor(owned.get().job, side.definition);
        side.started = owned.get().run.getStartTime() != null ? BusinessTime.instantOf(owned.get().run.getStartTime()) : current.startedAt;
        side.status = owned.get().run.getJobStatus() == null ? null : owned.get().run.getJobStatus().name();
        return side;
    }

    private static Map<String, Object> runView(Side side) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("jobQueueId", side.jobQueueId);
        v.put("attempt", side.row.attempt);
        v.put("pipelineVersion", side.version);
        v.put("startedAt", side.started == null ? null : side.started.toString());
        v.put("status", side.row.status);
        v.put("runStatus", side.status);
        return v;
    }

    private static Map<String, Object> sideView(Side side, ItemRow.StepEntry entry) {
        if (entry == null) {
            return null;
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("status", entry.status);
        v.put("rows", entry.n);
        v.put("hasData", entry.ds != null);
        v.put("reusedFromAttempt", entry.reusedFrom);
        return v;
    }

    private static ItemRow.StepEntry entry(ItemRow row, String stepKey) {
        for (ItemRow.StepEntry e : row.steps) {
            if (e.key.equals(stepKey)) {
                return e;
            }
        }
        return null;
    }

    /** The item's data at a step, masked, as JSON: one row an object, several an array; JSON text read as what it holds. */
    private JsonNode dataOf(RunItems.Rows rows, ItemRow.StepEntry entry, ItemMasks masks, Set<String> masked) {
        if (entry == null || entry.ds == null || entry.rows == null || entry.rows.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> found = rows.at(entry.ds, entry.rows.subList(0, Math.min(RunItems.MAX_DATA_ROWS, entry.rows.size())));
        if (found.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> shown = masks.rows(found, found.get(0).keySet(), masked);
        ArrayNode array = JSON.createArrayNode();
        for (Map<String, Object> row : shown) {
            ObjectNode o = JSON.createObjectNode();
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                o.set(cell.getKey(), valueNode(cell.getValue()));
            }
            array.add(o);
        }
        return array.size() == 1 ? array.get(0) : array;
    }

    private static JsonNode valueNode(Object value) {
        if (value instanceof String) {
            String t = ((String) value).trim();
            if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                try {
                    return JSON.readTree(t);
                } catch (Exception plainText) {
                    // a text that only looks like JSON
                }
            }
        }
        return JSON.valueToTree(value);
    }

    /** The changes from {@code before} to {@code after}, by path; true when there were more than {@value #MAX_CHANGES}. */
    static boolean diff(String path, JsonNode before, JsonNode after, List<Map<String, Object>> out) {
        if (out.size() >= MAX_CHANGES) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        if (before.isObject() && after.isObject()) {
            Set<String> names = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String at = path.isEmpty() ? name : path + "." + name;
                JsonNode b = before.get(name);
                JsonNode a = after.get(name);
                if (b == null) {
                    add(out, at, "added", null, a);
                } else if (a == null) {
                    add(out, at, "removed", b, null);
                } else if (diff(at, b, a, out)) {
                    return true;
                }
            }
            return out.size() >= MAX_CHANGES;
        }
        if (before.isArray() && after.isArray()) {
            int n = Math.max(before.size(), after.size());
            for (int i = 0; i < n; i++) {
                String at = path + "[" + i + "]";
                if (i >= before.size()) {
                    add(out, at, "added", null, after.get(i));
                } else if (i >= after.size()) {
                    add(out, at, "removed", before.get(i), null);
                } else if (diff(at, before.get(i), after.get(i), out)) {
                    return true;
                }
            }
            return out.size() >= MAX_CHANGES;
        }
        if (!before.equals(after)) {
            add(out, path, "changed", before, after);
        }
        return out.size() >= MAX_CHANGES;
    }

    private static void add(List<Map<String, Object>> out, String path, String kind, JsonNode before, JsonNode after) {
        if (out.size() >= MAX_CHANGES) {
            return;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("path", path);
        c.put("kind", kind);
        c.put("before", text(before));
        c.put("after", text(after));
        out.add(c);
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        String t = node.toString();
        return t.length() > MAX_VALUE ? t.substring(0, MAX_VALUE - 1) + "…" : t;
    }

    /** What changed in the pipeline between the versions the two runs ran: its steps added, removed, moved or set differently. */
    static Map<String, Object> pipelineChange(Side older, Side newer) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", older.version);
        p.put("to", newer.version);
        List<String> lines = changesBetween(older.definition, newer.definition);
        p.put("changes", lines);
        boolean sameVersion = Objects.equals(older.version, newer.version) && older.version != null;
        p.put("same", sameVersion || (lines.isEmpty() && older.definition != null && newer.definition != null));
        String note;
        if (older.definition == null || newer.definition == null) {
            note = "The pipeline version one of the runs ran cannot be read any more.";
        } else if (sameVersion) {
            note = String.format("Both runs ran pipeline version %d: any change is the data's, not the pipeline's.", newer.version);
        } else if (lines.isEmpty()) {
            note = String.format("Pipeline versions %s and %s have the same steps and settings.", older.version, newer.version);
        } else {
            note = String.format("Pipeline v%s to v%s: %s.", older.version, newer.version, String.join("; ", lines));
        }
        p.put("note", note);
        return p;
    }

    /** A step's settings never show their values here (a prompt, a query): only which ones changed. */
    static List<String> changesBetween(PipelineDefinition was, PipelineDefinition now) {
        List<String> lines = new ArrayList<>();
        if (was == null || now == null || was.getSteps() == null || now.getSteps() == null) {
            return lines;
        }
        Map<String, PipelineDefinition.Step> before = new LinkedHashMap<>();
        Map<String, Integer> beforeAt = new HashMap<>();
        for (int i = 0; i < was.getSteps().size(); i++) {
            before.put(was.getSteps().get(i).getKey(), was.getSteps().get(i));
            beforeAt.put(was.getSteps().get(i).getKey(), i);
        }
        Map<String, PipelineDefinition.Step> after = new LinkedHashMap<>();
        for (int i = 0; i < now.getSteps().size(); i++) {
            PipelineDefinition.Step step = now.getSteps().get(i);
            after.put(step.getKey(), step);
            PipelineDefinition.Step old = before.get(step.getKey());
            String name = nameOf(step);
            if (old == null) {
                lines.add(String.format("added step %s", name));
                continue;
            }
            List<String> what = new ArrayList<>();
            if (!Objects.equals(old.getTask(), step.getTask())) {
                what.add("its task");
            }
            if (!Objects.equals(nameOf(old), name)) {
                what.add("its name (was " + nameOf(old) + ")");
            }
            if (!Objects.equals(old.getInput(), step.getInput())) {
                what.add("its input");
            }
            Map<String, Object> oc = old.effectiveConfig() == null ? Collections.emptyMap() : old.effectiveConfig();
            Map<String, Object> nc = step.effectiveConfig() == null ? Collections.emptyMap() : step.effectiveConfig();
            Set<String> settings = new LinkedHashSet<>(oc.keySet());
            settings.addAll(nc.keySet());
            List<String> changedSettings = new ArrayList<>();
            for (String setting : settings) {
                if (!Objects.equals(JSON.valueToTree(oc.get(setting)), JSON.valueToTree(nc.get(setting)))) {
                    changedSettings.add(setting);
                }
            }
            if (!changedSettings.isEmpty()) {
                what.add((changedSettings.size() == 1 ? "its setting " : "its settings ") + String.join(", ", changedSettings.subList(0,
                    Math.min(6, changedSettings.size()))) + (changedSettings.size() > 6 ? " and more" : ""));
            }
            if (!Objects.equals(beforeAt.get(step.getKey()), i) && what.isEmpty()) {
                what.add("its place");
            }
            if (!what.isEmpty()) {
                lines.add(String.format("%s changed %s", name, String.join(", ", what)));
            }
        }
        Iterator<Map.Entry<String, PipelineDefinition.Step>> left = before.entrySet().iterator();
        while (left.hasNext()) {
            Map.Entry<String, PipelineDefinition.Step> e = left.next();
            if (!after.containsKey(e.getKey())) {
                lines.add(String.format("removed step %s", nameOf(e.getValue())));
            }
        }
        return lines;
    }

    private static String nameOf(PipelineDefinition.Step step) {
        return step.getName() == null || step.getName().trim().isEmpty() ? step.getKey() : step.getName().trim();
    }
}
