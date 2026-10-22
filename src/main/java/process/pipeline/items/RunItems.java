package process.pipeline.items;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import process.pipeline.registry.ItemRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import process.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.model.dto.ResponseDto;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.DatasetStore;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunOwnership;
import process.pipeline.StepStore;
import process.pipeline.StepTasks;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.RowSource;
import process.pipeline.data.Values;
import process.pipeline.review.RunReaders;
import process.security.ManagedSessionOnly;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * The Run Items view's reads (MIG-420), for the console: a run's items, one item with its steps, its data at a step, its
 * files, one file, all of them as a zip, the items as a CSV, the item counts of a page of runs and Home's runs with failed
 * items. The app is the controlled window onto each item's files and JSON: nobody is given the bucket.
 *
 * <ul>
 *   <li><b>Who.</b> The run's readers (RunReaders: its owner, an administrator, its reviewers); anyone else -- another
 *   workspace included -- is told the run is not found (404). A platform administrator's own sign-in reads no workspace's
 *   items (ManagedSessionOnly; the controller is @WorkspaceData too); a managed session does, audited at the controller.</li>
 *   <li><b>Masking.</b> Every value handed out -- the JSON, the table, the fields, the CSV -- goes through {@link ItemMasks}:
 *   the Data Catalog's masks for the person, and a sensitive pipeline's own columns read from its rows.</li>
 *   <li><b>Pointers only.</b> An item's data is read from its step's kept output by row position; an output past its retention
 *   says it was removed, never anything older.</li>
 *   <li><b>Files</b> stream from the run's kept files or, for an item's input object or an upload, through storage-service's
 *   trusted read; every file served or refused, and every zip and every link made, is in file_access_log.</li>
 * </ul>
 */
@Service
public class RunItems {

    private static final Logger logger = LoggerFactory.getLogger(RunItems.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String RUN_NOT_FOUND = "Run not found with jobQueueId.";
    public static final String ITEM_NOT_FOUND = "Item not found in this run.";
    static final int MAX_PAGE = 200;
    static final int MAX_DATA_ROWS = 50;
    static final int MAX_FIELDS = 60;
    static final long MAX_ZIP_BYTES = 500L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /** MIG-421: the most items one bulk action names (a selection's refs, a zip, a review task). */
    public static final int MAX_SELECTION = 1_000;
    /** MIG-421: the most items one zip holds (its link names them). */
    public static final int MAX_ZIP_ITEMS = 200;

    /** An answer: its HTTP status and its envelope, or a file to stream. */
    public static final class Answer {
        public final int status;
        public final ResponseDto body;
        public final String fileName;
        public final String contentType;
        public final StreamingResponseBody stream;

        private Answer(int status, ResponseDto body, String fileName, String contentType, StreamingResponseBody stream) {
            this.status = status;
            this.body = body;
            this.fileName = fileName;
            this.contentType = contentType;
            this.stream = stream;
        }

        static Answer ok(String message, Object data) {
            return new Answer(200, new ResponseDto(SUCCESS, message, data), null, null, null);
        }

        public static Answer refused(int status, String message) {
            return new Answer(status, new ResponseDto(ERROR, message), null, null, null);
        }

        static Answer file(String fileName, String contentType, StreamingResponseBody stream) {
            return new Answer(200, null, fileName, contentType, stream);
        }

        public boolean isFile() {
            return this.stream != null;
        }
    }

    /** The list's filters and page. */
    public static class ListRequest {
        public Long jobQueueId;
        public String status;
        public String search;
        public String parent;
        public Integer page;
        public Integer size;
        public List<String> fields;
        /** MIG-421: only these items (a selection, a Task inbox link), and not these ("Select all N matching" but some). */
        public List<String> refs;
        public List<String> except;

        public List<String> getRefs() { return refs; }

        public void setRefs(List<String> refs) { this.refs = refs; }

        public List<String> getExcept() { return except; }

        public void setExcept(List<String> except) { this.except = except; }

        public Long getJobQueueId() { return jobQueueId; }

        public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

        public String getStatus() { return status; }

        public void setStatus(String status) { this.status = status; }

        public String getSearch() { return search; }

        public void setSearch(String search) { this.search = search; }

        public String getParent() { return parent; }

        public void setParent(String parent) { this.parent = parent; }

        public Integer getPage() { return page; }

        public void setPage(Integer page) { this.page = page; }

        public Integer getSize() { return size; }

        public void setSize(Integer size) { this.size = size; }

        public List<String> getFields() { return fields; }

        public void setFields(List<String> fields) { this.fields = fields; }
    }

    /** An item's files: which, a page. */
    public static class FilesRequest {
        public Long jobQueueId;
        public String ref;
        public String stepKey;
        public String search;
        public Integer page;
        public Integer size;
        public Integer attempt;

        public Long getJobQueueId() { return jobQueueId; }

        public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

        public String getRef() { return ref; }

        public void setRef(String ref) { this.ref = ref; }

        public String getStepKey() { return stepKey; }

        public void setStepKey(String stepKey) { this.stepKey = stepKey; }

        public String getSearch() { return search; }

        public void setSearch(String search) { this.search = search; }

        public Integer getPage() { return page; }

        public void setPage(Integer page) { this.page = page; }

        public Integer getSize() { return size; }

        public void setSize(Integer size) { this.size = size; }

        public Integer getAttempt() { return attempt; }

        public void setAttempt(Integer attempt) { this.attempt = attempt; }
    }

    private final RunItemStore store;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final StepStore steps;
    private final PipelineDefinitionStore definitions;
    private final StepTasks tasks;
    private final DatasetStore datasets;
    private final ItemAccessLog accessLog;
    private RunReaders readers;
    private BucketStore buckets;
    private CatalogMasks catalog;
    private ItemLinks links;

    public RunItems(RunItemStore store, JobQueueRepository runs, SourceJobRepository jobs, StepStore steps, PipelineDefinitionStore definitions,
        StepTasks tasks, DatasetStore datasets, ItemAccessLog accessLog) {
        this.store = store;
        this.runs = runs;
        this.jobs = jobs;
        this.steps = steps;
        this.definitions = definitions;
        this.tasks = tasks;
        this.datasets = datasets;
        this.accessLog = accessLog;
    }

    @Autowired(required = false)
    public void useReaders(RunReaders readers) {
        this.readers = readers;
    }

    @Autowired(required = false)
    public void useBuckets(BucketStore buckets) {
        this.buckets = buckets;
    }

    @Autowired(required = false)
    public void useCatalog(CatalogMasks catalog) {
        this.catalog = catalog;
    }

    @Autowired(required = false)
    public void useLinks(ItemLinks links) {
        this.links = links;
    }

    // ---------------------------------------------------------------------------------------------------------- access

    /** The run, when the caller may read it; empty -- "not found" -- otherwise. */
    Optional<RunOwnership.Owned> readable(Long jobQueueId) {
        if (jobQueueId == null) {
            return Optional.empty();
        }
        return this.readers != null ? this.readers.readable(jobQueueId) : RunOwnership.owned(this.runs, this.jobs, jobQueueId);
    }

    /** Why the caller is refused before anything is read, or null: a platform administrator's own sign-in. */
    private static Answer platformRefusal() {
        return ManagedSessionOnly.isPlatformOwnSignIn() ? Answer.refused(403, ManagedSessionOnly.REFUSAL) : null;
    }

    /** The caller's masks for this run's data. */
    ItemMasks masksFor(SourceJob job, PipelineDefinition definition) {
        boolean sensitive = definition != null && "sensitive".equals(definition.effectiveSettings().effectiveSensitivity());
        if ("TENANT_ADMIN".equals(TenantContext.getUserRole())) {
            return ItemMasks.none();
        }
        JsonNode data = this.catalog == null ? null : this.catalog.forCaller();
        return ItemMasks.of(data, data != null, sensitive);
    }

    /** A run's steps as items see them, from the definition it ran. */
    private Steps stepsOf(long jobQueueId) {
        PipelineDefinition definition = null;
        try {
            Optional<PipelineDefinitionStore.Stored> stored = this.steps.pinnedDefinition(jobQueueId).flatMap(this.definitions::byId);
            definition = stored.isPresent() ? stored.get().definition() : null;
        } catch (RuntimeException unreadable) {
            logger.warn("Run {}: its definition cannot be read for its items: {}", jobQueueId, unreadable.getMessage());
        }
        List<ItemTracker.StepInfo> plan = definition == null ? Collections.<ItemTracker.StepInfo>emptyList() : ItemTracker.plan(definition, this.tasks);
        return new Steps(definition, plan);
    }

    /** MIG-421: a run's steps as items see them (for the across-runs list and the compare). */
    Steps stepsOfRun(long jobQueueId) {
        return this.stepsOf(jobQueueId);
    }

    static final class Steps {
        final PipelineDefinition definition;
        final List<ItemTracker.StepInfo> list;

        Steps(PipelineDefinition definition, List<ItemTracker.StepInfo> list) {
            this.definition = definition;
            this.list = list;
        }

        ItemTracker.StepInfo at(Integer index) {
            if (index == null) {
                return null;
            }
            for (ItemTracker.StepInfo step : this.list) {
                if (step.index == index) {
                    return step;
                }
            }
            return null;
        }

        ItemTracker.StepInfo byKey(String key) {
            for (ItemTracker.StepInfo step : this.list) {
                if (step.key.equals(key)) {
                    return step;
                }
            }
            return null;
        }

        String name(Integer index, String key) {
            ItemTracker.StepInfo step = this.at(index);
            return step != null ? step.name : key;
        }
    }

    // ------------------------------------------------------------------------------------------------------------ list

    public Answer list(ListRequest request) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(request == null ? null : request.jobQueueId);
        if (!owned.isPresent()) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        JobQueue run = owned.get().run;
        List<ItemRow> all = this.store.current(run.getJobQueueId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobQueueId", run.getJobQueueId());
        data.put("itemRun", !all.isEmpty());
        if (all.isEmpty()) {
            data.put("total", 0);
            data.put("items", Collections.emptyList());
            return Answer.ok("This run has no items: its pipeline does not work item by item.", data);
        }
        Steps steps = this.stepsOf(run.getJobQueueId());
        ItemMasks masks = this.masksFor(owned.get().job, steps.definition);
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        all.forEach(row -> byKey.put(row.itemKey, row));
        ItemRow parent = null;
        if (request.parent != null && !request.parent.trim().isEmpty()) {
            parent = byRef(all, request.parent.trim());
            if (parent == null) {
                return Answer.refused(404, ITEM_NOT_FOUND);
            }
        }
        final String parentKey = parent == null ? null : parent.itemKey;
        List<ItemRow> level = all.stream().filter(row -> parentKey == null ? row.parentKey == null : parentKey.equals(row.parentKey))
            .sorted((a, b) -> Integer.compare(a.position, b.position)).collect(Collectors.toList());
        data.put("summary", this.summaryOf(all, steps, run));
        data.put("steps", stepsView(steps));
        // The fields a person may add as columns: the columns of the items' last rows.
        Rows rows = new Rows(this.datasets, this.steps);
        List<String> available = new ArrayList<>();
        for (ItemRow row : level) {
            ItemRow.StepEntry last = lastData(row);
            if (last != null) {
                for (String c : rows.columns(last.ds)) {
                    if (!available.contains(c) && available.size() < MAX_FIELDS) {
                        available.add(c);
                    }
                }
            }
            if (available.size() >= MAX_FIELDS) {
                break;
            }
        }
        data.put("fields", available);
        data.put("masked", new ArrayList<>(masks.maskedNames(available)));
        String status = request.status == null ? "all" : request.status.trim().toLowerCase(Locale.ROOT);
        List<ItemRow> filtered = this.filter(level, status, request.search, request.refs, request.except, rows, masks);
        int size = Math.max(1, Math.min(MAX_PAGE, request.size == null ? 50 : request.size));
        int page = Math.max(0, request.page == null ? 0 : request.page);
        int from = Math.min(filtered.size(), page * size);
        List<ItemRow> shown = filtered.subList(from, Math.min(filtered.size(), from + size));
        List<String> wanted = request.fields == null ? Collections.<String>emptyList()
            : request.fields.stream().filter(available::contains).limit(MAX_FIELDS).collect(Collectors.toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemRow row : shown) {
            Map<String, Object> view = this.itemView(row, steps, byKey);
            if (!wanted.isEmpty()) {
                view.put("fields", this.fieldsOf(rows, row, masks, wanted));
            }
            items.add(view);
        }
        data.put("total", filtered.size());
        data.put("page", page);
        data.put("size", size);
        data.put("items", items);
        if (masks.failedClosed()) {
            data.put("maskNote", "Sensitive values are masked everywhere: the Data Catalog could not say which ones are yours to see.");
        }
        return Answer.ok(String.format("%d item(s).", filtered.size()), data);
    }

    /**
     * The items of one level that the status, the search and a selection (only these refs, not those) let through, in order. The
     * search matches what the person sees: their masks applied first, so a masked value is never found by its real text.
     */
    List<ItemRow> filter(List<ItemRow> level, String status, String searchText, List<String> refs, List<String> except, Rows rows, ItemMasks masks) {
        Set<String> only = refs == null || refs.isEmpty() ? null : refs.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet());
        Set<String> not = except == null ? Collections.<String>emptySet()
            : except.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet());
        List<ItemRow> filtered = new ArrayList<>();
        Search search = Search.of(searchText);
        for (ItemRow row : level) {
            if (only != null && !only.contains(row.itemRef)) {
                continue;
            }
            if (not.contains(row.itemRef)) {
                continue;
            }
            if (!matchesStatus(row, status)) {
                continue;
            }
            if (search != null && !search.matches(row, () -> this.fieldsOf(rows, row, masks, null))) {
                continue;
            }
            filtered.add(row);
        }
        return filtered;
    }

    /** MIG-421: what a bulk action on the Items tab acts on: the run, and the items its selection names (all pages), in order. */
    public static final class Selected {
        public final RunOwnership.Owned owned;
        public final List<ItemRow> items;
        public final ItemMasks masks;
        final Steps steps;

        Selected(RunOwnership.Owned owned, List<ItemRow> items, ItemMasks masks, Steps steps) {
            this.owned = owned;
            this.items = items;
            this.masks = masks;
            this.steps = steps;
        }
    }

    /**
     * MIG-421: the items a selection names -- the refs ticked, or every item the filter and search find ("Select all N
     * matching", across pages) less the ones unticked -- for a run the caller reads; null when the run is not theirs (404).
     */
    Selected selected(ListRequest request) {
        Optional<RunOwnership.Owned> owned = this.readable(request == null ? null : request.jobQueueId);
        if (!owned.isPresent()) {
            return null;
        }
        List<ItemRow> all = this.store.current(request.jobQueueId);
        Steps steps = this.stepsOf(request.jobQueueId);
        ItemMasks masks = this.masksFor(owned.get().job, steps.definition);
        String parentKey = null;
        if (request.parent != null && !request.parent.trim().isEmpty()) {
            ItemRow parent = byRef(all, request.parent.trim());
            parentKey = parent == null ? "\u0000none" : parent.itemKey;
        }
        final String level = parentKey;
        List<ItemRow> rows = all.stream().filter(row -> level == null ? row.parentKey == null : level.equals(row.parentKey))
            .sorted((a, b) -> Integer.compare(a.position, b.position)).collect(Collectors.toList());
        boolean ticked = request.refs != null && !request.refs.isEmpty();
        String status = ticked || request.status == null ? "all" : request.status.trim().toLowerCase(Locale.ROOT);
        List<ItemRow> chosen = this.filter(rows, status, ticked ? null : request.search, request.refs, request.except, new Rows(this.datasets, this.steps),
            masks);
        return new Selected(owned.get(), chosen, masks, steps);
    }

    /**
     * MIG-421: a selection's size, before a bulk action: how many items, how many of them failed (what "Retry N failed" retries),
     * their refs (at most {@value #MAX_SELECTION}) and the first names.
     */
    public Answer selection(ListRequest request) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Selected selected = this.selected(request);
        if (selected == null) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        int failed = 0;
        List<String> refs = new ArrayList<>();
        List<String> failedRefs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ItemRow row : selected.items) {
            boolean f = ItemRow.FAILED.equals(row.status) || row.childFailed > 0;
            failed += f ? 1 : 0;
            if (refs.size() < MAX_SELECTION) {
                refs.add(row.itemRef);
                if (f) {
                    failedRefs.add(row.itemRef);
                }
            }
            if (labels.size() < 5) {
                labels.add(row.label);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobQueueId", request.jobQueueId);
        data.put("items", selected.items.size());
        data.put("failed", failed);
        data.put("refs", refs);
        data.put("failedRefs", failedRefs);
        data.put("labels", labels);
        data.put("capped", selected.items.size() > MAX_SELECTION);
        return Answer.ok(String.format("%d item(s) selected, %d failed.", selected.items.size(), failed), data);
    }

    static boolean matchesStatus(ItemRow row, String status) {
        switch (status) {
            case "check":
                return ItemRow.CHECK.equals(row.flag);
            case "done":
                return ItemRow.DONE.equals(row.status) && row.childFailed == 0;
            case "failed":
                return ItemRow.FAILED.equals(row.status) || row.childFailed > 0;
            case "running":
                return ItemRow.RUNNING.equals(row.status);
            default:
                return true;
        }
    }

    /** A search: text in the item's name or key or any of its values, or "field = value" / "field: value". */
    static final class Search {
        final String text;
        final String field;
        final String value;

        private Search(String text, String field, String value) {
            this.text = text;
            this.field = field;
            this.value = value;
        }

        static Search of(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String s = raw.trim();
            int eq = s.indexOf('=');
            int colon = s.indexOf(':');
            int at = eq > 0 ? eq : colon > 0 && !s.contains("/") ? colon : -1;
            if (at > 0) {
                String field = s.substring(0, at).trim();
                String value = s.substring(at + 1).trim();
                if (!field.isEmpty() && !value.isEmpty() && field.matches("[A-Za-z0-9_. -]{1,128}")) {
                    return new Search(null, field.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
                }
            }
            return new Search(s.toLowerCase(Locale.ROOT), null, null);
        }

        boolean matches(ItemRow row, Supplier<Map<String, Object>> fields) {
            if (this.text != null) {
                if (row.label.toLowerCase(Locale.ROOT).contains(this.text) || row.itemKey.toLowerCase(Locale.ROOT).contains(this.text)
                    || row.itemRef.equals(this.text) || (row.reason != null && row.reason.toLowerCase(Locale.ROOT).contains(this.text))) {
                    return true;
                }
                for (Object v : fields.get().values()) {
                    if (v != null && String.valueOf(v).toLowerCase(Locale.ROOT).contains(this.text)) {
                        return true;
                    }
                }
                return false;
            }
            for (Map.Entry<String, Object> e : fields.get().entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(this.field) && e.getValue() != null
                    && String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT).equals(this.value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The item's last step that has its own rows: where its fields are read. */
    static ItemRow.StepEntry lastData(ItemRow row) {
        ItemRow.StepEntry last = null;
        for (ItemRow.StepEntry entry : row.steps) {
            if (entry.ds != null && entry.rows != null && !entry.rows.isEmpty() && !ItemRow.StepEntry.SHARED.equals(entry.status)) {
                last = entry;
            }
        }
        return last;
    }

    /** The item's scalar fields from its last row, masked; only {@code wanted} when named. */
    Map<String, Object> fieldsOf(Rows rows, ItemRow row, ItemMasks masks, List<String> wanted) {
        ItemRow.StepEntry last = lastData(row);
        if (last == null) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> found = rows.at(last.ds, Collections.singletonList(last.rows.get(0)));
        if (found.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> shown = masks.rows(found, found.get(0).keySet(), new HashSet<>());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> cell : shown.get(0).entrySet()) {
            if (wanted != null && !wanted.contains(cell.getKey())) {
                continue;
            }
            Object v = cell.getValue();
            String text = v == null ? null : String.valueOf(Values.text(v) == null ? v : Values.text(v));
            out.put(cell.getKey(), text != null && text.length() > 200 ? text.substring(0, 199) + "…" : v);
        }
        return out;
    }

    /** One item as a row of the list. */
    Map<String, Object> itemView(ItemRow row, Steps steps, Map<String, ItemRow> byKey) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ref", row.itemRef);
        view.put("key", row.itemKey);
        view.put("label", row.label);
        int slash = row.itemKey.lastIndexOf('/');
        view.put("folder", row.parentKey == null && slash > 0 ? row.itemKey.substring(0, slash + 1) : null);
        ItemRow parent = row.parentKey == null ? null : byKey.get(row.parentKey);
        view.put("parentRef", parent == null ? null : parent.itemRef);
        view.put("position", row.position);
        view.put("status", row.status);
        if (row.failedStepIndex != null) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("index", row.failedStepIndex);
            failed.put("key", row.failedStepKey);
            failed.put("name", steps.name(row.failedStepIndex, row.failedStepKey));
            view.put("failedStep", failed);
        } else {
            view.put("failedStep", null);
        }
        view.put("reason", row.reason);
        view.put("attempt", row.attempt);
        // MIG-421: an OCR page to check by hand (a document: one of its pages is).
        view.put("flag", row.flag);
        if (row.childCount > 0) {
            Map<String, Object> children = new LinkedHashMap<>();
            children.put("total", row.childCount);
            children.put("failed", row.childFailed);
            children.put("done", row.childCount - row.childFailed);
            view.put("children", children);
        } else {
            view.put("children", null);
        }
        view.put("fileCount", row.fileCount);
        view.put("durationMs", row.durationMs);
        view.put("startedAt", row.startedAt == null ? null : row.startedAt.toString());
        view.put("endedAt", row.endedAt == null ? null : row.endedAt.toString());
        Map<String, Object> cells = new LinkedHashMap<>();
        for (ItemRow.StepEntry entry : row.steps) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("status", entry.status);
            cell.put("rows", entry.n);
            cell.put("files", entry.files);
            cell.put("sharedBy", entry.sharedBy);
            cell.put("reused", entry.reusedFrom != null);
            cell.put("hasData", entry.ds != null);
            cells.put(entry.key, cell);
        }
        view.put("cells", cells);
        return view;
    }

    static List<Map<String, Object>> stepsView(Steps steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItemTracker.StepInfo step : steps.list) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("index", step.index);
            s.put("key", step.key);
            s.put("name", step.name);
            s.put("task", step.task);
            s.put("role", step.role.word());
            out.add(s);
        }
        return out;
    }

    /** The strip above the table: items, done, failed, running, files, the failing steps, the duration. */
    Map<String, Object> summaryOf(List<ItemRow> all, Steps steps, JobQueue run) {
        int items = 0;
        int done = 0;
        int failed = 0;
        int running = 0;
        int subItems = 0;
        int failedLeaves = 0;
        int check = 0;
        int files = 0;
        long ms = 0;
        int timed = 0;
        Map<String, Integer> failedAt = new LinkedHashMap<>();
        for (ItemRow row : all) {
            files += row.fileCount;
            check += row.parentKey != null && ItemRow.CHECK.equals(row.flag) ? 1 : 0;
            if (row.parentKey != null) {
                subItems++;
                if (ItemRow.FAILED.equals(row.status)) {
                    failedLeaves++;
                    failedAt.merge(steps.name(row.failedStepIndex, row.failedStepKey), 1, Integer::sum);
                }
                continue;
            }
            items++;
            if (row.durationMs != null) {
                ms += row.durationMs;
                timed++;
            }
            if (ItemRow.RUNNING.equals(row.status)) {
                running++;
            } else if (ItemRow.FAILED.equals(row.status) || row.childFailed > 0) {
                failed++;
                if (ItemRow.FAILED.equals(row.status)) {
                    failedLeaves++;
                    failedAt.merge(steps.name(row.failedStepIndex, row.failedStepKey), 1, Integer::sum);
                }
            } else {
                done++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("items", items);
        summary.put("done", done);
        summary.put("failed", failed);
        summary.put("running", running);
        summary.put("subItems", subItems);
        summary.put("failedItems", failedLeaves);
        // MIG-421: pages (or items) an OCR step marked Check by hand.
        summary.put("check", check + (int) all.stream().filter(r -> r.parentKey == null && ItemRow.CHECK.equals(r.flag) && r.childCount == 0).count());
        summary.put("files", files);
        summary.put("averageMs", timed == 0 ? null : ms / timed);
        List<Map<String, Object>> at = new ArrayList<>();
        failedAt.forEach((name, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", name);
            m.put("count", n);
            at.add(m);
        });
        summary.put("failedAt", at);
        summary.put("runStatus", run.getJobStatus() == null ? null : run.getJobStatus().name());
        boolean inFlight = run.getJobStatus() != null && run.getJobStatus().isInFlight();
        summary.put("retryable", failedLeaves > 0 && !inFlight);
        summary.put("retryWhy", failedLeaves == 0 ? "No item failed." : inFlight ? "The run is still running; retry once it has finished." : null);
        // What a sub-item is called: a split's pages, else sub-items.
        String noun = null;
        for (ItemTracker.StepInfo step : steps.list) {
            if (step.role == ItemRole.FAN_OUT && step.subKey != null) {
                noun = "page".equals(step.subKey) ? "page" : step.subKey.replace('_', ' ');
            }
        }
        summary.put("childNoun", noun == null ? "sub-item" : noun);
        ItemTracker.StepInfo source = steps.list.isEmpty() ? null : steps.at(ItemTracker.sourceOf(steps.list));
        summary.put("source", source == null ? null : source.name);
        return summary;
    }

    static ItemRow byRef(List<ItemRow> rows, String ref) {
        for (ItemRow row : rows) {
            if (row.itemRef.equals(ref)) {
                return row;
            }
        }
        // A key with no '/' names it too (the customer API's {itemKey}).
        for (ItemRow row : rows) {
            if (row.itemKey.equals(ref)) {
                return row;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------- detail

    public Answer detail(Long jobQueueId, String ref, Integer attempt) {
        return this.detail(jobQueueId, ref, attempt, null, null);
    }

    /** One item; previous and next walk the items the list shows with this status and search. */
    public Answer detail(Long jobQueueId, String ref, Integer attempt, String status, String search) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        List<ItemRow> all = this.store.current(jobQueueId);
        ItemRow current = ref == null ? null : byRef(all, ref.trim());
        if (current == null) {
            return Answer.refused(404, ITEM_NOT_FOUND);
        }
        List<ItemRow> versions = this.store.versions(jobQueueId, current.itemKey);
        ItemRow row = current;
        if (attempt != null && attempt != current.attempt) {
            row = versions.stream().filter(v -> v.attempt == attempt).findFirst().orElse(null);
            if (row == null) {
                return Answer.refused(404, String.format("This item has no version from attempt %d.", attempt));
            }
        }
        Steps steps = this.stepsOf(jobQueueId);
        Map<String, ItemRow> byKey = new LinkedHashMap<>();
        all.forEach(r -> byKey.put(r.itemKey, r));
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> item = this.itemView(row, steps, byKey);
        String wanted = status == null || status.trim().isEmpty() ? "all" : status.trim().toLowerCase(Locale.ROOT);
        Search filter = Search.of(search);
        ItemMasks siblingMasks = filter == null ? null : this.masksFor(owned.get().job, steps.definition);
        Rows siblingRows = new Rows(this.datasets, this.steps);
        List<ItemRow> siblings = all.stream().filter(r -> Objects.equals(r.parentKey, current.parentKey))
            .filter(r -> r == current || r.itemKey.equals(current.itemKey) || (matchesStatus(r, wanted)
                && (filter == null || filter.matches(r, () -> this.fieldsOf(siblingRows, r, siblingMasks, null)))))
            .sorted((a, b) -> Integer.compare(a.position, b.position)).collect(Collectors.toList());
        int at = siblings.indexOf(current);
        item.put("index", at + 1);
        item.put("of", siblings.size());
        item.put("prevRef", at > 0 ? siblings.get(at - 1).itemRef : null);
        item.put("nextRef", at >= 0 && at < siblings.size() - 1 ? siblings.get(at + 1).itemRef : null);
        item.put("current", row.current);
        data.put("item", item);
        List<Map<String, Object>> stepViews = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (ItemRow.StepEntry entry : row.steps) {
            seen.add(entry.i);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("index", entry.i);
            s.put("key", entry.key);
            s.put("name", steps.name(entry.i, entry.key));
            s.put("task", entry.task);
            s.put("status", entry.status);
            s.put("note", entry.note);
            s.put("durationMs", entry.ms);
            s.put("rows", entry.n);
            s.put("files", entry.files);
            s.put("sharedBy", entry.sharedBy);
            s.put("hasData", entry.ds != null);
            s.put("reusedFromAttempt", entry.reusedFrom);
            stepViews.add(s);
        }
        // The steps it never reached: not run, and why.
        for (ItemTracker.StepInfo step : steps.list) {
            if (step.index >= ItemTracker.sourceOf(steps.list) && !seen.contains(step.index)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("index", step.index);
                s.put("key", step.key);
                s.put("name", step.name);
                s.put("task", step.task);
                s.put("status", ItemRow.StepEntry.NOT_RUN);
                s.put("note", ItemRow.FAILED.equals(row.status) ? "Not run: the step before failed." : row.finished() ? "Not run for this item."
                    : "Not run yet.");
                s.put("hasData", false);
                stepViews.add(s);
            }
        }
        stepViews.sort((a, b) -> Integer.compare((Integer) a.get("index"), (Integer) b.get("index")));
        data.put("steps", stepViews);
        int count = this.countFilesOf(row, null, null);
        data.put("fileCount", count);
        data.put("files", this.filesView(this.filesOf(row, null, null, 0, 20), steps));
        List<Map<String, Object>> vs = new ArrayList<>();
        for (ItemRow v : versions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempt", v.attempt);
            m.put("status", v.status);
            m.put("current", v.current);
            m.put("endedAt", v.endedAt == null ? null : v.endedAt.toString());
            m.put("reason", v.reason);
            vs.add(m);
        }
        data.put("versions", vs);
        Map<String, Object> retry = new LinkedHashMap<>();
        boolean failedHere = ItemRow.FAILED.equals(current.status) || current.childFailed > 0;
        boolean inFlight = owned.get().run.getJobStatus() != null && owned.get().run.getJobStatus().isInFlight();
        retry.put("possible", failedHere && !inFlight && row.current);
        retry.put("fromStep", current.failedStepIndex == null ? null : steps.name(current.failedStepIndex, current.failedStepKey));
        retry.put("fromStepIndex", current.failedStepIndex);
        retry.put("why", !failedHere ? "Only a failed item is retried." : inFlight ? "The run is still running; retry once it has finished." : null);
        data.put("retry", retry);
        return Answer.ok(row.label, data);
    }

    List<Map<String, Object>> filesView(List<ItemRow.FileRow> files, Steps steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = Instant.now();
        for (ItemRow.FileRow f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileRef", f.runItemFileId);
            m.put("name", f.fileName);
            m.put("role", f.role);
            m.put("stepKey", f.stepKey);
            m.put("stepName", steps.name(f.stepIndex, f.stepKey));
            String format = f.format != null ? f.format : extensionOf(f.fileName);
            m.put("format", format);
            m.put("bytes", f.byteCount);
            m.put("previewable", PREVIEWABLE.contains(format == null ? "" : format.toLowerCase(Locale.ROOT)));
            m.put("expired", f.expiresAt != null && !f.expiresAt.isAfter(now));
            m.put("fileId", f.fileId);
            out.add(m);
        }
        return out;
    }

    static final Set<String> PREVIEWABLE = new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "json",
        "csv", "jsonl", "md", "html", "m4a", "mp3", "wav", "ogg"));

    static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot <= 0 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------------------------- data

    public Answer data(Long jobQueueId, String ref, String stepKey, Integer attempt) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        ItemRow row = this.version(jobQueueId, ref, attempt);
        if (row == null) {
            return Answer.refused(404, ITEM_NOT_FOUND);
        }
        Steps steps = this.stepsOf(jobQueueId);
        ItemRow.StepEntry entry = null;
        for (ItemRow.StepEntry e : row.steps) {
            if (e.key.equals(stepKey)) {
                entry = e;
            }
        }
        if (entry == null) {
            return Answer.refused(404, "This item has no data at that step.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepKey", entry.key);
        data.put("stepName", steps.name(entry.i, entry.key));
        data.put("status", entry.status);
        data.put("sharedBy", entry.sharedBy);
        data.put("rowCount", entry.n);
        if (entry.ds == null) {
            data.put("columns", Collections.emptyList());
            data.put("rows", Collections.emptyList());
            data.put("note", "This step kept no data for the item.");
            return Answer.ok("No data.", data);
        }
        Optional<StepStore.DatasetFile> file = this.steps.datasetById(entry.ds);
        if (!file.isPresent() || (file.get().expiresAt != null && !file.get().expiresAt.isAfter(Instant.now()))) {
            data.put("expired", true);
            data.put("columns", Collections.emptyList());
            data.put("rows", Collections.emptyList());
            data.put("note", "This step's data was removed with the run's datasets (past its retention).");
            return Answer.ok("Removed.", data);
        }
        Rows rows = new Rows(this.datasets, this.steps);
        List<Integer> positions = entry.rows == null || entry.rows.isEmpty()
            ? rangeOf(Math.min(MAX_DATA_ROWS, entry.sharedBy == null ? 0 : MAX_DATA_ROWS)) : entry.rows;
        List<Map<String, Object>> found = rows.at(entry.ds, positions.subList(0, Math.min(MAX_DATA_ROWS, positions.size())));
        List<String> columns = rows.columns(entry.ds);
        Set<String> masked = new LinkedHashSet<>();
        ItemMasks masks = this.masksFor(owned.get().job, steps.definition);
        data.put("columns", columns);
        data.put("rows", masks.rows(found, columns, masked));
        data.put("masked", new ArrayList<>(masked));
        data.put("truncated", entry.n != null && entry.n > found.size());
        data.put("shared", entry.rows == null || entry.rows.isEmpty());
        if (masks.failedClosed()) {
            data.put("maskNote", "Sensitive values are masked everywhere: the Data Catalog could not say which ones are yours to see.");
        }
        return Answer.ok(String.format("%d row(s).", found.size()), data);
    }

    private static List<Integer> rangeOf(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    /** The item's current row, or its version from {@code attempt}. */
    private ItemRow version(long jobQueueId, String ref, Integer attempt) {
        ItemRow current = ref == null ? null : byRef(this.store.current(jobQueueId), ref.trim());
        if (current == null || attempt == null || attempt == current.attempt) {
            return current;
        }
        return this.store.versions(jobQueueId, current.itemKey).stream().filter(v -> v.attempt == attempt).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------------------------------------------------ files

    public Answer files(FilesRequest request) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(request == null ? null : request.jobQueueId);
        if (!owned.isPresent()) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        ItemRow row = this.version(request.jobQueueId, request.ref, request.attempt);
        if (row == null) {
            return Answer.refused(404, ITEM_NOT_FOUND);
        }
        int size = Math.max(1, Math.min(MAX_PAGE, request.size == null ? 15 : request.size));
        int page = Math.max(0, request.page == null ? 0 : request.page);
        int total = this.countFilesOf(row, request.stepKey, request.search);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ref", row.itemRef);
        data.put("label", row.label);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        long bytes = 0;
        for (ItemRow.FileRow f : this.filesOf(row, request.stepKey, null, 0, 10_000)) {
            bytes += f.byteCount == null ? 0 : f.byteCount;
        }
        data.put("bytes", bytes);
        data.put("files", this.filesView(this.filesOf(row, request.stepKey, request.search, page * size, size), this.stepsOf(row.jobQueueId)));
        return Answer.ok(String.format("%d file(s).", total), data);
    }

    /** One of an item's files, to show (inline) or to save. */
    public Answer file(Long jobQueueId, String ref, Long fileRef, boolean inline) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        String action = inline ? ItemAccessLog.PREVIEW : ItemAccessLog.DOWNLOAD;
        if (!owned.isPresent()) {
            this.accessLog.refusedRun(jobQueueId, ItemAccessLog.ITEM_FILE, action, 404, RUN_NOT_FOUND);
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        JobQueue run = owned.get().run;
        Optional<ItemRow.FileRow> file = fileRef == null ? Optional.empty() : this.store.file(fileRef);
        ItemRow row = ref == null ? null : byRef(this.store.current(jobQueueId), ref.trim());
        boolean belongs = file.isPresent() && row != null && file.get().jobQueueId == jobQueueId
            && (file.get().itemKey.equals(row.itemKey) || this.store.versions(jobQueueId, row.itemKey).stream()
                .anyMatch(v -> v.attempt == file.get().attempt && v.itemKey.equals(file.get().itemKey))
                // ZT-I16: a page's file, at the page's current version, is its document's too.
                || (family(row) && file.get().itemKey.startsWith(row.itemKey + "#") && this.store.versions(jobQueueId, file.get().itemKey)
                    .stream().anyMatch(v -> v.current && v.attempt == file.get().attempt)));
        if (!belongs) {
            this.accessLog.log(run, ItemAccessLog.ITEM_FILE, action, null, false, 404, "File not found for this item.");
            return Answer.refused(404, "File not found for this item.");
        }
        ItemRow.FileRow f = file.get();
        String type = contentTypeOf(f.fileName, f.format);
        StreamingResponseBody body;
        try {
            body = this.open(run, f);
        } catch (Gone gone) {
            this.accessLog.log(run, ItemAccessLog.ITEM_FILE, action, f.fileName, false, 410, gone.getMessage());
            return Answer.refused(410, gone.getMessage());
        }
        this.accessLog.log(run, ItemAccessLog.ITEM_FILE, action, f.fileName, true, 200, null);
        return Answer.file(f.fileName, type, body);
    }

    /** A file that is not there any more. */
    static final class Gone extends Exception {
        Gone(String message) {
            super(message);
        }
    }

    /** The file's bytes: the run's kept file, or the bucket object (the item's input, an upload). Opened before anything is sent. */
    private StreamingResponseBody open(JobQueue run, ItemRow.FileRow f) throws Gone {
        if (f.runDatasetId != null) {
            Optional<StepStore.DatasetFile> kept = this.steps.datasetById(f.runDatasetId);
            if (!kept.isPresent() || (kept.get().expiresAt != null && !kept.get().expiresAt.isAfter(Instant.now()))) {
                throw new Gone("This file was removed with the run's datasets (past its retention).");
            }
            String key = kept.get().storageKey;
            return out -> {
                try (InputStream in = this.datasets.openFile(key)) {
                    copy(in, out, MAX_FILE_BYTES);
                } catch (IOException | RuntimeException failed) {
                    throw failed;
                } catch (Exception failed) {
                    throw new IOException(failed.getMessage(), failed);
                }
            };
        }
        if (f.bucketAlias != null && f.objectKey != null && this.buckets != null) {
            long tenantId = run.getTenantId();
            String alias = f.bucketAlias;
            String key = f.objectKey;
            InputStream in;
            try {
                in = this.buckets.open(tenantId, alias, key, MAX_FILE_BYTES);
            } catch (Exception gone) {
                throw new Gone("This file is not in its bucket any more: " + gone.getMessage());
            }
            return out -> {
                try (InputStream opened = in) {
                    copy(opened, out, MAX_FILE_BYTES);
                }
            };
        }
        throw new Gone("This file cannot be read here.");
    }

    static void copy(InputStream in, OutputStream out, long most) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
            total += n;
            if (total > most) {
                throw new IOException("The file is bigger than the console downloads.");
            }
            out.write(chunk, 0, n);
        }
    }

    static String contentTypeOf(String name, String format) {
        String ext = format != null ? format.toLowerCase(Locale.ROOT) : extensionOf(name);
        if (ext == null) {
            return "application/octet-stream";
        }
        switch (ext) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "json": return "application/json";
            case "jsonl": return "application/x-ndjson; charset=utf-8";
            case "csv": return "text/csv";
            case "txt":
            case "md": return "text/plain; charset=utf-8";
            case "html": return "text/html; charset=utf-8";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            default: return "application/octet-stream";
        }
    }

    // ------------------------------------------------------------------------------------------------------------- zip

    /** A signed link to an item's files as one zip (one step's, or all), after the access check; logged. */
    public Answer zipLink(Long jobQueueId, String ref, String stepKey) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            this.accessLog.refusedRun(jobQueueId, ItemAccessLog.ITEM_ZIP, ItemAccessLog.LINK, 404, RUN_NOT_FOUND);
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        ItemRow row = ref == null ? null : byRef(this.store.current(jobQueueId), ref.trim());
        if (row == null) {
            return Answer.refused(404, ITEM_NOT_FOUND);
        }
        if (this.links == null || !this.links.available()) {
            return Answer.refused(503, "Download links cannot be made right now.");
        }
        int count = this.countFilesOf(row, stepKey, null);
        if (count == 0) {
            return Answer.refused(404, "This item has no files to download.");
        }
        Long userId = TenantContext.getAppUserId();
        String[] issued = this.links.issue(owned.get().run.getTenantId(), userId == null ? 0 : userId, jobQueueId, row.itemRef, stepKey);
        this.accessLog.log(owned.get().run, ItemAccessLog.ITEM_ZIP, ItemAccessLog.LINK, row.label, true, 200, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", issued[0]);
        data.put("expiresAt", issued[1]);
        data.put("files", count);
        return Answer.ok("A download link, valid two minutes.", data);
    }

    /** The zip a link opens: the caller's own access is checked again, and the link must be theirs, for this run and item. */
    public Answer zip(Long jobQueueId, String token) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        ItemLinks.Link link = this.links == null ? null : this.links.check(token);
        Long tenant = TenantContext.getTenantId();
        Long userId = TenantContext.getAppUserId();
        if (link == null || jobQueueId == null || link.jobQueueId != jobQueueId || tenant == null || link.tenantId != tenant
            || link.userId != (userId == null ? 0 : userId)) {
            this.accessLog.refusedRun(jobQueueId, ItemAccessLog.ITEM_ZIP, ItemAccessLog.DOWNLOAD, 403,
                "The download link is not good: expired, or not yours.");
            return Answer.refused(403, "This download link is expired or is not yours. Ask for the files again.");
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            this.accessLog.refusedRun(jobQueueId, ItemAccessLog.ITEM_ZIP, ItemAccessLog.DOWNLOAD, 404, RUN_NOT_FOUND);
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        JobQueue run = owned.get().run;
        if (link.itemRef.startsWith(MANY)) {
            return this.zipOfMany(run, owned.get(), link);
        }
        ItemRow row = byRef(this.store.current(jobQueueId), link.itemRef);
        if (row == null) {
            return Answer.refused(404, ITEM_NOT_FOUND);
        }
        List<ItemRow.FileRow> files = this.filesOf(row, link.stepKey, null, 0, 5_000);
        List<Object[]> opened = new ArrayList<>();
        long total = 0;
        for (ItemRow.FileRow f : files) {
            total += f.byteCount == null ? 0 : f.byteCount;
            if (total > MAX_ZIP_BYTES) {
                return Answer.refused(413, "These files are more than 500 MB together; download them one by one.");
            }
            try {
                opened.add(new Object[] {f, this.open(run, f)});
            } catch (Gone gone) {
                logger.info("Run {} item {}: {} left out of the zip: {}", jobQueueId, row.itemRef, f.fileName, gone.getMessage());
            }
        }
        this.accessLog.log(run, ItemAccessLog.ITEM_ZIP, ItemAccessLog.DOWNLOAD, row.label, true, 200, null);
        String name = String.format("run-%d-item-%s%s.zip", jobQueueId, row.itemRef, link.stepKey == null ? "" : "-" + safe(link.stepKey));
        return Answer.file(name, "application/zip", out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                Set<String> taken = new HashSet<>();
                for (Object[] pair : opened) {
                    ItemRow.FileRow f = (ItemRow.FileRow) pair[0];
                    String entry = safe(f.stepKey) + "/" + unique(safe(f.fileName), taken);
                    zip.putNextEntry(new ZipEntry(entry));
                    ((StreamingResponseBody) pair[1]).writeTo(new FilterOutputStream(zip) {
                        @Override
                        public void close() {
                            // the zip stays open for the next entry
                        }
                    });
                    zip.closeEntry();
                }
            }
        });
    }

    /** MIG-421: a link's item names several items: "items:" and their refs. */
    static final String MANY = "items:";

    /**
     * MIG-421: a signed link to the selected items' files as one zip -- each item a folder of its own, by its ref and name --
     * after the access check; logged. The link names the items it opens (at most {@value #MAX_ZIP_ITEMS}) and nothing else.
     */
    public Answer zipLinkOfMany(ListRequest selection, String stepKey) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Selected selected = this.selected(selection);
        if (selected == null) {
            this.accessLog.refusedRun(selection == null ? null : selection.jobQueueId, ItemAccessLog.ITEM_ZIP, ItemAccessLog.LINK, 404, RUN_NOT_FOUND);
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        if (selected.items.isEmpty()) {
            return Answer.refused(404, "No item is selected.");
        }
        if (selected.items.size() > MAX_ZIP_ITEMS) {
            return Answer.refused(413, String.format("A zip holds at most %d items; %d are selected. Narrow the selection.", MAX_ZIP_ITEMS,
                selected.items.size()));
        }
        if (this.links == null || !this.links.available()) {
            return Answer.refused(503, "Download links cannot be made right now.");
        }
        int files = 0;
        for (ItemRow row : selected.items) {
            files += this.countFilesOf(row, stepKey, null);
        }
        // ZT-I21: an item with no files of its own (a row of a table pipeline) still has its folder -- its item.json, masked.
        Long userId = TenantContext.getAppUserId();
        String refs = selected.items.stream().map(r -> r.itemRef).collect(Collectors.joining(","));
        String[] issued = this.links.issue(selected.owned.run.getTenantId(), userId == null ? 0 : userId, selected.owned.run.getJobQueueId(),
            MANY + refs, stepKey);
        this.accessLog.log(selected.owned.run, ItemAccessLog.ITEM_ZIP, ItemAccessLog.LINK, String.format("%d items", selected.items.size()), true, 200,
            null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", issued[0]);
        data.put("expiresAt", issued[1]);
        data.put("items", selected.items.size());
        data.put("files", files);
        return Answer.ok("A download link, valid two minutes.", data);
    }

    /** The zip a link to several items opens: a folder per item -- its files, and item.json with its status and values (masked). */
    private Answer zipOfMany(JobQueue run, RunOwnership.Owned owned, ItemLinks.Link link) {
        List<ItemRow> all = this.store.current(run.getJobQueueId());
        List<ItemRow> chosen = new ArrayList<>();
        for (String ref : link.itemRef.substring(MANY.length()).split(",")) {
            ItemRow row = byRef(all, ref.trim());
            if (row == null) {
                return Answer.refused(404, ITEM_NOT_FOUND);
            }
            chosen.add(row);
        }
        Steps steps = this.stepsOf(run.getJobQueueId());
        ItemMasks masks = this.masksFor(owned.job, steps.definition);
        Rows rows = new Rows(this.datasets, this.steps);
        List<Object[]> entries = new ArrayList<>();
        long total = 0;
        Set<String> folders = new HashSet<>();
        for (ItemRow row : chosen) {
            String folder = unique(safe(row.itemRef + "-" + row.label), folders);
            Map<String, Object> about = new LinkedHashMap<>();
            about.put("ref", row.itemRef);
            about.put("item", row.label);
            about.put("key", row.itemKey);
            about.put("status", row.status);
            about.put("failedStep", row.failedStepIndex == null ? null : steps.name(row.failedStepIndex, row.failedStepKey));
            about.put("reason", row.reason);
            about.put("flag", row.flag);
            about.put("attempt", row.attempt);
            Map<String, Object> steps2 = new LinkedHashMap<>();
            for (ItemRow.StepEntry entry : row.steps) {
                steps2.put(steps.name(entry.i, entry.key), entry.status);
            }
            about.put("steps", steps2);
            about.put("values", this.fieldsOf(rows, row, masks, null));
            entries.add(new Object[] {folder + "/item.json", about});
            for (ItemRow.FileRow f : this.filesOf(row, link.stepKey, null, 0, 5_000)) {
                total += f.byteCount == null ? 0 : f.byteCount;
                if (total > MAX_ZIP_BYTES) {
                    return Answer.refused(413, "These files are more than 500 MB together; download fewer items at a time.");
                }
                try {
                    entries.add(new Object[] {folder + "/" + safe(f.stepKey), f, this.open(run, f)});
                } catch (Gone gone) {
                    logger.info("Run {} item {}: {} left out of the zip: {}", run.getJobQueueId(), row.itemRef, f.fileName, gone.getMessage());
                }
            }
        }
        this.accessLog.log(run, ItemAccessLog.ITEM_ZIP, ItemAccessLog.DOWNLOAD, String.format("%d items", chosen.size()), true, 200, null);
        String name = String.format("run-%d-%d-items%s.zip", run.getJobQueueId(), chosen.size(), link.stepKey == null ? "" : "-" + safe(link.stepKey));
        return Answer.file(name, "application/zip", out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                Set<String> taken = new HashSet<>();
                for (Object[] e : entries) {
                    if (e.length == 2) {
                        zip.putNextEntry(new ZipEntry((String) e[0]));
                        zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(e[1]));
                        zip.closeEntry();
                        continue;
                    }
                    ItemRow.FileRow f = (ItemRow.FileRow) e[1];
                    zip.putNextEntry(new ZipEntry(unique(e[0] + "/" + safe(f.fileName), taken)));
                    ((StreamingResponseBody) e[2]).writeTo(new FilterOutputStream(zip) {
                        @Override
                        public void close() {
                            // the zip stays open for the next entry
                        }
                    });
                    zip.closeEntry();
                }
            }
        });
    }

    static String safe(String name) {
        String s = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._ -]+", "_").replaceAll("^[. ]+", "");
        return s.isEmpty() ? "file" : s;
    }

    static String unique(String name, Set<String> taken) {
        if (taken.add(name)) {
            return name;
        }
        for (int n = 2; ; n++) {
            int dot = name.lastIndexOf('.');
            String next = dot > 0 ? name.substring(0, dot) + "-" + n + name.substring(dot) : name + "-" + n;
            if (taken.add(next)) {
                return next;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------- csv

    /** The items table as a CSV, the person's masks applied. */
    public Answer csv(Long jobQueueId, String status, String search, List<String> fields) {
        ListRequest request = new ListRequest();
        request.jobQueueId = jobQueueId;
        request.status = status;
        request.search = search;
        request.fields = fields;
        return this.csv(request);
    }

    /** MIG-421: the CSV of a selection (the refs ticked, or all matching less the unticked), masked as the table is. */
    public Answer csv(ListRequest selection) {
        ListRequest request = new ListRequest();
        request.jobQueueId = selection == null ? null : selection.jobQueueId;
        boolean ticked = selection != null && selection.refs != null && !selection.refs.isEmpty();
        request.status = selection == null || ticked ? null : selection.status;
        request.search = selection == null || ticked ? null : selection.search;
        request.parent = selection == null ? null : selection.parent;
        request.refs = selection == null ? null : selection.refs;
        request.except = selection == null ? null : selection.except;
        request.size = MAX_PAGE;
        request.fields = selection == null ? null : selection.fields;
        Long jobQueueId = request.jobQueueId;
        List<String> fields = request.fields;
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            return Answer.refused(404, RUN_NOT_FOUND);
        }
        List<Map<String, Object>> all = new ArrayList<>();
        List<String> available = null;
        for (int page = 0; ; page++) {
            request.page = page;
            Answer one = this.list(request);
            if (one.status != 200) {
                return one;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) one.body.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
            @SuppressWarnings("unchecked")
            List<String> f = (List<String>) data.get("fields");
            available = f;
            all.addAll(items);
            if (items.size() < MAX_PAGE) {
                break;
            }
        }
        Steps steps = this.stepsOf(jobQueueId);
        List<String> wanted = fields == null || available == null ? Collections.<String>emptyList()
            : fields.stream().filter(available::contains).collect(Collectors.toList());
        String name = String.format("run-%d-items.csv", jobQueueId);
        this.accessLog.log(owned.get().run, ItemAccessLog.ITEM_FILE, ItemAccessLog.DOWNLOAD, name, true, 200, null);
        return Answer.file(name, "text/csv", out -> {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            try (CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
                List<String> header = new ArrayList<>(Arrays.asList("#", "item", "key", "status", "failed_step", "reason", "duration_ms",
                    "files"));
                for (ItemTracker.StepInfo step : steps.list) {
                    header.add(step.name);
                }
                header.addAll(wanted);
                csv.printRecord(header);
                for (Map<String, Object> item : all) {
                    List<Object> record = new ArrayList<>();
                    record.add(item.get("ref"));
                    record.add(item.get("label"));
                    record.add(item.get("key"));
                    record.add(item.get("status"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> failed = (Map<String, Object>) item.get("failedStep");
                    record.add(failed == null ? "" : failed.get("name"));
                    record.add(item.get("reason"));
                    record.add(item.get("durationMs"));
                    record.add(item.get("fileCount"));
                    @SuppressWarnings("unchecked")
                    Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) item.get("cells");
                    for (ItemTracker.StepInfo step : steps.list) {
                        Map<String, Object> cell = cells == null ? null : cells.get(step.key);
                        record.add(cell == null ? "" : cell.get("status"));
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> values = (Map<String, Object>) item.get("fields");
                    for (String field : wanted) {
                        Object v = values == null ? null : values.get(field);
                        record.add(v == null ? "" : cellText(v));
                    }
                    csv.printRecord(record);
                }
            }
        });
    }

    /** A value as a CSV cell; a formula-looking text is quoted as text (no spreadsheet runs it). */
    static String cellText(Object value) {
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    // ------------------------------------------------------------------------------------------- runs list and Home

    /** The item counts of a page of runs (the Runs list's "48 of 50 done · 2 failed"); runs the caller may not read are left out. */
    public Answer counts(Collection<Long> jobQueueIds) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        List<Long> ids = jobQueueIds == null ? Collections.<Long>emptyList()
            : jobQueueIds.stream().filter(Objects::nonNull).distinct().limit(MAX_PAGE).collect(Collectors.toList());
        Map<Long, int[]> counts = this.store.counts(ids);
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            if (!"TENANT_ADMIN".equals(TenantContext.getUserRole()) && !this.readable(e.getKey()).isPresent()) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("items", e.getValue()[0]);
            c.put("done", e.getValue()[1]);
            c.put("failed", e.getValue()[2]);
            c.put("running", e.getValue()[3]);
            data.put(String.valueOf(e.getKey()), c);
        }
        return Answer.ok(String.format("%d run(s) with items.", data.size()), data);
    }

    /** Home's Needs attention: the caller's runs of the last week that ended with failed items, newest first. */
    public Answer attention(Integer limit) {
        Answer refused = platformRefusal();
        if (refused != null) {
            return refused;
        }
        int most = Math.max(1, Math.min(20, limit == null ? 5 : limit));
        List<RunItemStore.Attention> found = this.store.attention(Instant.now().minus(7, ChronoUnit.DAYS), most * 3);
        Map<Long, SourceJob> jobsById = new HashMap<>();
        this.jobs.findAllById(found.stream().map(a -> a.jobId).collect(Collectors.toSet())).forEach(j -> jobsById.put(j.getJobId(), j));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunItemStore.Attention a : found) {
            if (out.size() >= most) {
                break;
            }
            if (!this.readable(a.jobQueueId).isPresent()) {
                continue;
            }
            Steps steps = this.stepsOf(a.jobQueueId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobQueueId", a.jobQueueId);
            m.put("jobId", a.jobId);
            SourceJob job = jobsById.get(a.jobId);
            m.put("jobName", job == null ? null : job.getJobName());
            m.put("endedAt", a.endedAt == null ? null : a.endedAt.toString());
            m.put("failed", a.failed);
            m.put("items", a.items);
            m.put("labels", a.labels);
            List<String> names = new ArrayList<>();
            for (String key : a.stepKeys) {
                ItemTracker.StepInfo step = steps.byKey(key);
                names.add(step == null ? key : step.name);
            }
            m.put("failedAt", names);
            out.add(m);
        }
        return Answer.ok(String.format("%d run(s) with failed items.", out.size()), out);
    }

    // ------------------------------------------------------------------------------------------------- dataset rows

    /** Rows of run datasets by position, each dataset's columns read once; nothing is held beyond what is asked. */
    static final class Rows {
        private final DatasetStore datasets;
        private final StepStore steps;
        private final Map<Long, List<String>> columns = new HashMap<>();
        private final Map<Long, Map<Integer, Map<String, Object>>> cache = new HashMap<>();

        Rows(DatasetStore datasets, StepStore steps) {
            this.datasets = datasets;
            this.steps = steps;
        }

        List<String> columns(Long ds) {
            if (ds == null) {
                return Collections.emptyList();
            }
            return this.columns.computeIfAbsent(ds, id -> {
                Optional<StepStore.DatasetFile> file = this.steps.datasetById(id);
                if (!file.isPresent() || (file.get().expiresAt != null && !file.get().expiresAt.isAfter(Instant.now()))) {
                    return Collections.<String>emptyList();
                }
                try (RowSource rows = this.datasets.open(file.get().storageKey, 1)) {
                    return new ArrayList<>(rows.columns());
                } catch (Exception gone) {
                    return Collections.<String>emptyList();
                }
            });
        }

        List<Map<String, Object>> at(Long ds, List<Integer> positions) {
            if (ds == null || positions == null || positions.isEmpty()) {
                return Collections.emptyList();
            }
            Map<Integer, Map<String, Object>> held = this.cache.computeIfAbsent(ds, k -> new HashMap<>());
            TreeSet<Integer> missing = new TreeSet<>();
            for (Integer p : positions) {
                if (!held.containsKey(p)) {
                    missing.add(p);
                }
            }
            if (!missing.isEmpty()) {
                Optional<StepStore.DatasetFile> file = this.steps.datasetById(ds);
                if (file.isPresent() && (file.get().expiresAt == null || file.get().expiresAt.isAfter(Instant.now()))) {
                    int p = 0;
                    try (RowSource rows = this.datasets.open(file.get().storageKey, 500)) {
                        for (List<Map<String, Object>> batch = rows.next(); batch != null && p <= missing.last(); batch = rows.next()) {
                            for (Map<String, Object> row : batch) {
                                if (missing.contains(p)) {
                                    held.put(p, row);
                                }
                                p++;
                            }
                        }
                    } catch (Exception gone) {
                        logger.info("Run dataset {} could not be read for its items: {}", ds, gone.getMessage());
                    }
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Integer p : positions) {
                Map<String, Object> row = held.get(p);
                if (row != null) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** For the customer API and the event relay: a run's current items, as stored. */
    public List<ItemRow> currentOf(long jobQueueId) {
        return this.store.current(jobQueueId);
    }

    /** For the customer API: one item's files at its current attempt (a document's with its pages'). */
    public List<ItemRow.FileRow> filesOf(ItemRow row) {
        return this.filesOf(row, null, null, 0, 1_000);
    }

    /** ZT-I16: a document's current version owns its pages' files too (a split's page images, a per-page step's files). */
    private static boolean family(ItemRow row) {
        return row.parentKey == null && row.childCount > 0 && row.current;
    }

    private List<ItemRow.FileRow> filesOf(ItemRow row, String stepKey, String search, int offset, int limit) {
        return family(row) ? this.store.familyFiles(row.jobQueueId, row.itemKey, stepKey, search, offset, limit)
            : this.store.files(row.jobQueueId, row.itemKey, row.attempt, stepKey, search, offset, limit);
    }

    private int countFilesOf(ItemRow row, String stepKey, String search) {
        return family(row) ? this.store.countFamilyFiles(row.jobQueueId, row.itemKey, stepKey, search)
            : this.store.countFiles(row.jobQueueId, row.itemKey, row.attempt, stepKey, search);
    }

    /** For the customer API: step names of a run. */
    public Function<ItemRow.StepEntry, String> stepNamer(long jobQueueId) {
        Steps steps = this.stepsOf(jobQueueId);
        return entry -> steps.name(entry.i, entry.key);
    }

    /** The definition a run ran (its pinned version), or null. */
    public PipelineDefinition definitionOf(long jobQueueId) {
        return this.stepsOf(jobQueueId).definition;
    }

    /** For the customer API: the name of the step an item failed at. */
    public String stepName(long jobQueueId, Integer index, String key) {
        return this.stepsOf(jobQueueId).name(index, key);
    }
}
