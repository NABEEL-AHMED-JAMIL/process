package process.pipeline.items;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import process.pipeline.PipelineDefinition;
import process.pipeline.StepTask;
import process.pipeline.StepTasks;
import process.pipeline.data.RowSource;
import process.pipeline.data.Values;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.TreeMap;

/**
 * The items of one attempt of one run (MIG-420, the Run Items view), as the step engine runs its steps.
 *
 * <b>Only item pipelines.</b> A pipeline is one when a step works item by item ({@link ItemRole#EACH}: transcribe, convert, AI
 * prompt, measure, Call API per row, one file or report per row) or splits items ({@link ItemRole#FAN_OUT}: split pages). Its
 * items are named by the first step that can name them: a {@link ItemRole#SOURCE} (Read S3 as a list: one item per object;
 * Read documents: one per document), else that first per-item step itself, by its input's rows. A run with more than
 * {@value #MAX_ITEMS} items (or {@value #MAX_SUB_ITEMS} sub-items) keeps none, and its log says so: a big table read whole is
 * never an item pipeline, and a per-row step over a million rows is not one either.
 *
 * <b>The item key</b> is a column the rows carry (the identity): the object key for files, extraction_id for documents,
 * else the first column of the per-item step's input that names each row once ({@link #CANDIDATES}, then a column called id or
 * ending in _id whose values are all different). A row's item is found by that column after every step; a per-item step's
 * output is matched by position (it keeps each row in order and leaves out only the rows it says failed), so a step that
 * rewrites the column (Convert file's key) still follows its items. With no such column at all, the items are the per-item
 * step's rows by position, followed only while a step keeps the rows in order.
 *
 * <b>Each step</b> leaves on each item a pointer into its kept output (run_dataset id and row positions), never a copy of
 * the data; the reason in words when the item failed there (the step's own words, {@link process.pipeline.StepContext#itemFailed});
 * the files it made for the item; the time it spent on it. A combining step (join, aggregate, dedupe, pivot, append) says
 * its output is shared by the items that went in. An item whose rows a step leaves out (a filter) ends there.
 *
 * <b>Sub-items</b>: a fan-out step's rows of one item, by its sub key (a page), each with its own status, data, files and
 * retry; the parent shows how many of them failed. One bad page never fails the document.
 *
 * <b>Retries</b> (a resume of the run, or of some items): the items rerun get a new row for this attempt -- the steps before
 * the one it resumes from copied as "reused" pointers, their files too -- and the earlier row is kept, no longer current.
 * In a retry of some items each step from the resume on reads only those items' rows ({@link #keepPosition}), and every
 * per-row call is keyed by its item ({@link #tagOf}), so the others are neither run nor billed again.
 */
public final class ItemTracker {

    private static final Logger logger = LoggerFactory.getLogger(ItemTracker.class);

    public static final int MAX_ITEMS = 1_000;
    public static final int MAX_SUB_ITEMS = 10_000;
    /** The most row positions one item keeps per step (its rows beyond them are counted, not pointed at). */
    public static final int MAX_POSITIONS = 500;
    /** The most rows a step's output may have for its items to be followed. */
    public static final long MAX_SCAN_ROWS = 200_000;
    /** The columns that name an item, in the order they are tried. */
    static final List<String> CANDIDATES = Arrays.asList("key", "extraction_id", "file_key", "object_key", "document_id", "record_id", "item_id",
        "id");

    /** What the tracker needs from the engine's execution. */
    public interface Engine {
        /** A dataset by its storage key, a batch at a time; never null. */
        RowSource open(String datasetKey) throws Exception;

        /** The storage key of a run dataset, or null when it is gone. */
        String storageKeyOf(long runDatasetId);

        /** A line in the run's own log. */
        void audit(String line);
    }

    /** A step of the definition, as items see it. */
    public static final class StepInfo {
        public final int index;
        public final String key;
        public final String name;
        public final String task;
        public final ItemRole role;
        public final String identity;
        public final String subKey;
        public final String bucket;
        public final boolean readsFiles;

        StepInfo(int index, String key, String name, String task, ItemRole role, String identity, String subKey, String bucket,
            boolean readsFiles) {
            this.index = index;
            this.key = key;
            this.name = name;
            this.task = task;
            this.role = role;
            this.identity = identity;
            this.subKey = subKey;
            this.bucket = bucket;
            this.readsFiles = readsFiles;
        }
    }

    /** What one step came to, as the engine hands it over. */
    public static final class Outcome {
        public int index;
        public long stepExecutionId;
        /** The dataset the step read (null: the run's source) and its run_dataset id when known. */
        public String inputKey;
        /** The reference the step left: its output, or the input it passed on; null when it failed. */
        public String outputKey;
        public Long outputDatasetId;
        public boolean completed;
        public String error;
        public List<ItemReport> reports = Collections.emptyList();
        public long millis;
        /** The run's input bucket (an inbox's), for an item's input file when its row names none. */
        public String runBucket;
    }

    /** Which steps of a definition make it an item pipeline, and their roles; empty when it is not one. */
    public static List<StepInfo> plan(PipelineDefinition definition, StepTasks tasks) {
        List<StepInfo> steps = new ArrayList<>();
        if (definition == null || definition.getSteps() == null) {
            return steps;
        }
        boolean items = false;
        List<PipelineDefinition.Step> list = definition.getSteps();
        for (int i = 0; i < list.size(); i++) {
            PipelineDefinition.Step step = list.get(i);
            Optional<StepTask> task = tasks.find(step.getTask());
            TaskSpec spec = task.map(StepTask::spec).orElse(null);
            Map<String, Object> config = step.effectiveConfig();
            ItemRole role = spec == null ? ItemRole.PASS : spec.itemRoleFor(config);
            items |= role.makesItemPipeline();
            Object bucket = config == null ? null : config.get("bucket");
            boolean readsFiles = spec != null && spec.configSchema() != null && spec.configSchema().get("properties") instanceof Map
                && ((Map<?, ?>) spec.configSchema().get("properties")).containsKey("keyColumn");
            steps.add(new StepInfo(i, step.getKey(), step.getName() == null || step.getName().trim().isEmpty() ? step.getKey() : step.getName(),
                step.getTask(), role, spec == null ? null : spec.itemIdentity(), spec == null ? null : spec.itemSubKey(),
                bucket == null ? null : String.valueOf(bucket), readsFiles));
        }
        return items ? steps : Collections.<StepInfo>emptyList();
    }

    /** The first step that names the run's items, or -1. */
    static int sourceOf(List<StepInfo> steps) {
        for (StepInfo step : steps) {
            if (step.role.namesItems()) {
                return step.index;
            }
        }
        return -1;
    }

    private final long jobQueueId;
    private final int attempt;
    private final RunItemStore store;
    private final Engine engine;
    private final List<StepInfo> steps;
    private final int sourceIndex;

    /** This attempt's items, by key: the ones it found, or (a retry) the ones it reruns. */
    private final Map<String, ItemRow> items = new LinkedHashMap<>();
    /** Every item's row before this attempt (a retry), by key. */
    private final Map<String, ItemRow> before = new LinkedHashMap<>();
    /** Items that ended before the run did (left out by a step, or failed): no longer followed. */
    private final Set<String> ended = new HashSet<>();
    /** An identity value (as a later step's rows hold it) -> the top-level item's key. */
    private final Map<String, String> alias = new HashMap<>();
    /** A dataset's storage key -> row position -> item key. */
    private final Map<String, Map<Integer, String>> mappings = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    /** ZT-I10: the items a step of this attempt took part in (a retried item no step reached is not done). */
    private final Set<String> touched = new HashSet<>();
    private final List<ItemRow.FileRow> files = new ArrayList<>();

    private String identity;
    private String subColumn;
    private boolean positional;
    /** Why this run keeps no items (more than the caps); null while it does. */
    private String off;
    private int topCount;
    private final Map<String, Integer> subCounts = new HashMap<>();
    private int subTotal;
    /** A retry: the first step that runs, and the items it reruns (null: all of them). */
    private int fromIndex = -1;
    private Set<String> retry;
    private int previousAttempt;
    private boolean restart;
    private boolean retired;

    private ItemTracker(long jobQueueId, int attempt, RunItemStore store, Engine engine, List<StepInfo> steps) {
        this.jobQueueId = jobQueueId;
        this.attempt = attempt;
        this.store = store;
        this.engine = engine;
        this.steps = steps;
        this.sourceIndex = sourceOf(steps);
    }

    /**
     * The tracker of a new attempt; null when the pipeline is not an item pipeline. {@code resumeFrom}: the first step a resume
     * runs (-1 for a first attempt or a restart), {@code previousAttempt} its earlier attempt, {@code itemKeys} the items it
     * reruns (null: every one).
     */
    public static ItemTracker open(long jobQueueId, int attempt, RunItemStore store, Engine engine, List<StepInfo> steps, int resumeFrom,
        int previousAttempt, List<String> itemKeys) {
        if (store == null || steps == null || steps.isEmpty() || sourceOf(steps) < 0) {
            return null;
        }
        ItemTracker tracker = new ItemTracker(jobQueueId, attempt, store, engine, steps);
        try {
            tracker.start(resumeFrom, previousAttempt, itemKeys);
        } catch (RuntimeException unread) {
            logger.warn("Run {} attempt {}: its items cannot be followed: {}", jobQueueId, attempt, unread.getMessage());
            engine.audit("Items: this attempt's items are not kept (" + unread.getMessage() + "); its steps and outputs show as usual.");
            return null;
        }
        return tracker;
    }

    /**
     * The tracker of an attempt that continues (after an Approval step): its items as it left them, followed from the step
     * after {@code fromIndex}.
     */
    public static ItemTracker continued(long jobQueueId, int attempt, RunItemStore store, Engine engine, List<StepInfo> steps) {
        if (store == null || steps == null || steps.isEmpty() || sourceOf(steps) < 0) {
            return null;
        }
        ItemTracker tracker = new ItemTracker(jobQueueId, attempt, store, engine, steps);
        List<ItemRow> mine = store.ofAttempt(jobQueueId, attempt);
        if (mine.isEmpty()) {
            return null;
        }
        for (ItemRow row : mine) {
            tracker.items.put(row.itemKey, row);
            tracker.learn(row);
            if (row.finished()) {
                tracker.ended.add(row.itemKey);
            }
        }
        tracker.retired = true;
        return tracker;
    }

    private void start(int resumeFrom, int previousAttempt, List<String> itemKeys) {
        this.previousAttempt = previousAttempt;
        if (resumeFrom < 0) {
            this.restart = this.attempt > 1;
            return;
        }
        this.fromIndex = resumeFrom;
        this.retry = itemKeys == null ? null : new LinkedHashSet<>(itemKeys);
        for (ItemRow row : this.store.current(this.jobQueueId)) {
            this.before.put(row.itemKey, row);
            this.learn(row);
        }
        if (this.before.isEmpty()) {
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (ItemRow was : this.before.values()) {
            if (!this.reruns(was.itemKey) || (this.retry != null && was.parentKey == null && this.hasRetriedChild(was.itemKey)
                && !this.retry.contains(was.itemKey))) {
                // Not rerun -- or a document only some of whose pages are: it keeps its row, recounted at the end.
                continue;
            }
            ItemRow row = new ItemRow();
            row.jobQueueId = this.jobQueueId;
            row.attempt = this.attempt;
            row.itemKey = was.itemKey;
            row.itemRef = was.itemRef;
            row.parentKey = was.parentKey;
            row.label = was.label;
            row.position = was.position;
            row.startedAt = now;
            for (ItemRow.StepEntry entry : was.steps) {
                if (entry.i < resumeFrom) {
                    ItemRow.StepEntry reused = entry.copy();
                    reused.reusedFrom = entry.reusedFrom != null ? entry.reusedFrom : was.attempt;
                    row.put(reused);
                }
            }
            this.items.put(row.itemKey, row);
            this.dirty.add(row.itemKey);
            // The reused steps' files are the item's in this attempt too.
            for (ItemRow.FileRow f : this.store.files(this.jobQueueId, was.itemKey, was.attempt, null, null, 0, 10_000)) {
                if (f.stepIndex < resumeFrom) {
                    ItemRow.FileRow copy = new ItemRow.FileRow();
                    copy.jobQueueId = this.jobQueueId;
                    copy.attempt = this.attempt;
                    copy.itemKey = f.itemKey;
                    copy.stepIndex = f.stepIndex;
                    copy.stepKey = f.stepKey;
                    copy.role = f.role;
                    copy.stepExecutionId = f.stepExecutionId;
                    copy.fileName = f.fileName;
                    copy.bucketAlias = ItemRow.FileRow.INPUT.equals(f.role) ? f.bucketAlias : null;
                    copy.objectKey = ItemRow.FileRow.INPUT.equals(f.role) ? f.objectKey : null;
                    copy.byteCount = f.byteCount;
                    this.files.add(copy);
                    row.fileCount++;
                }
            }
        }
        this.flush();
    }

    /** What an earlier row teaches: its identity and sub key, its number, its sub-items' numbers. */
    private void learn(ItemRow row) {
        for (ItemRow.StepEntry entry : row.steps) {
            if (entry.idc != null && this.identity == null) {
                this.identity = entry.idc;
                this.positional = "#".equals(entry.idc);
            }
            if (entry.subc != null && this.subColumn == null) {
                this.subColumn = entry.subc;
            }
        }
        if (row.parentKey == null) {
            this.topCount = Math.max(this.topCount, row.position);
            this.alias.put(row.itemKey, row.itemKey);
        } else {
            this.subCounts.merge(row.parentKey, row.position, Math::max);
            this.subTotal++;
        }
    }

    private boolean hasRetriedChild(String key) {
        for (String wanted : this.retry) {
            if (wanted.startsWith(key + "#")) {
                return true;
            }
        }
        return false;
    }

    /** Whether this attempt reruns the item: all of them, or one asked for, or a sub-item of one asked for. */
    private boolean reruns(String key) {
        if (this.retry == null) {
            return true;
        }
        for (String wanted : this.retry) {
            if (key.equals(wanted) || key.startsWith(wanted + "#") || wanted.startsWith(key + "#")) {
                return true;
            }
        }
        return false;
    }

    /** Whether this attempt keeps no items. */
    public boolean off() {
        return this.off != null;
    }

    /** Whether step {@code index} of this attempt reads only the retried items' rows. */
    public boolean filters(int index) {
        return this.off == null && this.retry != null && this.fromIndex >= 0 && index >= this.fromIndex && !this.before.isEmpty();
    }

    /**
     * For a retry of some items: whether the row at {@code position} of the dataset {@code key} stays in the step's input -- a
     * row of an item it reruns (or of a document one of whose pages it reruns), and, for a per-item step, never a row of no
     * item. Positions it cannot place stay for a step that combines or passes rows, and go for one that works item by item.
     */
    public boolean keepRow(int stepIndex, String datasetKey, int position, Map<String, Object> row) {
        Map<Integer, String> mapping = this.mappingOf(datasetKey);
        String key = mapping == null ? null : mapping.get(position);
        if (key == null) {
            // A row the earlier attempt never pointed at (the input of the step that named the items): by its key column.
            key = this.keyOf(row);
        }
        if (key == null) {
            return !this.steps.get(stepIndex).role.makesItemPipeline();
        }
        return this.reruns(key);
    }

    /** The positions a filter kept, in order: the filtered dataset's rows keep their items. */
    public void filtered(String fromKey, String toKey, List<Integer> keptPositions) {
        Map<Integer, String> mapping = this.mappingOf(fromKey);
        Map<Integer, String> next = new HashMap<>();
        for (int p = 0; p < keptPositions.size(); p++) {
            String key = mapping == null ? null : mapping.get(keptPositions.get(p));
            if (key != null) {
                next.put(p, key);
            }
        }
        this.mappings.put(toKey, next);
    }

    /** The item a dataset's rows belong to, by position: what this attempt worked out, else what earlier rows point at. */
    private Map<Integer, String> mappingOf(String datasetKey) {
        if (datasetKey == null) {
            return null;
        }
        Map<Integer, String> known = this.mappings.get(datasetKey);
        if (known != null) {
            return known;
        }
        Map<Integer, String> built = new HashMap<>();
        Map<Long, String> storage = new HashMap<>();
        List<ItemRow> rows = new ArrayList<>(this.before.values());
        rows.addAll(this.items.values());
        for (ItemRow row : rows) {
            for (ItemRow.StepEntry entry : row.steps) {
                if (entry.ds == null || entry.rows == null) {
                    continue;
                }
                String key = storage.computeIfAbsent(entry.ds, this.engine::storageKeyOf);
                if (datasetKey.equals(key)) {
                    for (Integer p : entry.rows) {
                        // ZT-I9: a row before the split is its document's -- a page's copies of its document's earlier entries
                        // point at the document's row, which stays the document's; a row after the split is the page's.
                        built.merge(p, row.itemKey, ItemTracker::ownerOf);
                    }
                }
            }
        }
        this.mappings.put(datasetKey, built);
        return built;
    }

    /** Of two items pointing at one row, the one it belongs to: a document over its own page's copy, else the deeper one. */
    static String ownerOf(String a, String b) {
        if (b.startsWith(a + "#")) {
            return a;
        }
        if (a.startsWith(b + "#")) {
            return b;
        }
        return a.length() >= b.length() ? a : b;
    }

    /**
     * The key a per-row call of this row is made under: a short token of its item (the same in every attempt), or its number
     * when it belongs to none.
     */
    public String tagOf(Map<String, Object> row, int index) {
        if (this.off != null || this.positional || row == null) {
            return String.valueOf(index + 1);
        }
        String key = this.keyOf(row);
        if (key == null && this.identity != null) {
            String raw = text(row.get(this.identity));
            if (raw != null) {
                key = raw;
                String sub = this.subColumn == null ? null : text(row.get(this.subColumn));
                if (sub != null) {
                    key = key + "#" + sub;
                }
            }
        }
        return key == null ? String.valueOf(index + 1) : "i" + sha256(key).substring(0, 16);
    }

    /** A row's item (or sub-item) key, or null when it belongs to none this tracker knows. */
    String keyOf(Map<String, Object> row) {
        if (row == null || this.identity == null || this.positional) {
            return null;
        }
        String value = text(row.get(this.identity));
        if (value == null) {
            return null;
        }
        String top = this.alias.get(value);
        if (top == null && (this.items.containsKey(value) || this.before.containsKey(value))) {
            top = value;
        }
        if (top == null) {
            return null;
        }
        if (this.subColumn != null) {
            String sub = text(row.get(this.subColumn));
            if (sub != null) {
                String key = top + "#" + sub;
                if (this.items.containsKey(key) || this.before.containsKey(key)) {
                    return key;
                }
            }
        }
        return top;
    }

    /**
     * Before the step that names the items runs: which column names them, from its input's columns (and, for a column called
     * id or ending in _id, its values, which must all differ). Nothing for any other step.
     */
    public void beforeStep(int index, String inputKey, List<String> sourceColumns) {
        if (this.off != null || index != this.sourceIndex || this.identity != null) {
            return;
        }
        StepInfo step = this.steps.get(index);
        if (step.role == ItemRole.SOURCE) {
            this.identity = step.identity;
            return;
        }
        List<String> columns;
        try (RowSource rows = inputKey == null ? null : this.engine.open(inputKey)) {
            columns = rows == null ? sourceColumns : rows.columns();
        } catch (Exception unread) {
            columns = sourceColumns;
        }
        columns = columns == null ? Collections.<String>emptyList() : columns;
        for (String candidate : CANDIDATES) {
            if (!"id".equals(candidate) && columns.contains(candidate)) {
                this.identity = candidate;
                return;
            }
        }
        if (inputKey != null) {
            for (String column : columns) {
                String c = column.toLowerCase(Locale.ROOT);
                if ((c.equals("id") || c.endsWith("_id")) && this.unique(inputKey, column)) {
                    this.identity = column;
                    return;
                }
            }
        }
        if (step.readsFiles) {
            // The run's own files (an inbox's): each row the step makes names its file by key.
            this.identity = "key";
            return;
        }
        this.positional = true;
        this.identity = "#";
    }

    private boolean unique(String datasetKey, String column) {
        Set<String> seen = new HashSet<>();
        long n = 0;
        try (RowSource rows = this.engine.open(datasetKey)) {
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                for (Map<String, Object> row : batch) {
                    String v = text(row.get(column));
                    if (v == null || !seen.add(v) || ++n > MAX_SCAN_ROWS) {
                        return false;
                    }
                }
            }
        } catch (Exception unread) {
            return false;
        }
        return n > 0;
    }

    /** A step completed or failed: its items' entries, statuses and files, written at once. */
    public void afterStep(Outcome outcome) {
        if (this.off != null || outcome.index < this.sourceIndex) {
            return;
        }
        try {
            this.step(outcome);
        } catch (RuntimeException unfollowed) {
            logger.warn("Run {} step {}: its items could not be followed: {}", this.jobQueueId, outcome.index, unfollowed.getMessage(), unfollowed);
        }
        this.flush();
    }

    private void step(Outcome o) {
        StepInfo step = this.steps.get(o.index);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, List<ItemReport>> reports = new HashMap<>();
        Map<Integer, String> inMap = this.mappingOf(o.inputKey);
        List<Rows> out = o.completed && o.outputKey != null && !o.outputKey.equals(o.inputKey) ? this.read(o.outputKey) : null;
        if (out == ROWS_TOO_MANY) {
            this.stop(String.format("step <%s> has more than %,d rows to follow", step.key, MAX_SCAN_ROWS));
            return;
        }
        if (o.index == this.sourceIndex && this.items.isEmpty() && this.before.isEmpty()) {
            if (!this.create(step, o, inMap, out, now)) {
                return;
            }
            inMap = this.mappingOf(o.inputKey);
        }
        // Each report to its item (a sub-item made here from its own row too).
        for (ItemReport report : o.reports) {
            String key = step.role == ItemRole.FAN_OUT && this.isSubRow(step, report.row) ? this.subItem(step, report.row, now)
                : this.keyOf(report.row);
            if (key != null) {
                reports.computeIfAbsent(key, k -> new ArrayList<>()).add(report);
            }
        }
        Set<String> wentIn = new HashSet<>();
        if (inMap != null) {
            for (String key : inMap.values()) {
                wentIn.add(key);
                ItemRow row = this.items.get(key);
                if (row != null && row.parentKey != null) {
                    wentIn.add(row.parentKey);
                }
            }
        }
        if (o.index == this.sourceIndex || o.inputKey == null) {
            wentIn.addAll(this.items.keySet());
        }
        wentIn.addAll(reports.keySet());
        Map<Integer, String> outMap;
        Long ds = o.outputDatasetId;
        if (!o.completed) {
            outMap = Collections.emptyMap();
        } else if (out == null) {
            // The step passed its input on: its rows are the input's.
            outMap = inMap == null ? Collections.<Integer, String>emptyMap() : inMap;
        } else {
            outMap = this.mapOutput(step, inMap, out, reports, now);
        }
        if (o.completed && o.outputKey != null) {
            this.mappings.put(o.outputKey, outMap);
        }
        Map<String, List<Integer>> positions = new HashMap<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(outMap).entrySet()) {
            positions.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        int sharedBy = 0;
        if (step.role == ItemRole.SHARED) {
            for (String key : wentIn) {
                ItemRow row = this.items.get(key);
                if (row != null && row.parentKey == null) {
                    sharedBy++;
                }
            }
        }
        boolean anyReportedFailure = false;
        for (List<ItemReport> list : reports.values()) {
            for (ItemReport r : list) {
                anyReportedFailure |= r.kind == ItemReport.Kind.FAILED;
            }
        }
        String firstFailed = null;
        for (Map.Entry<String, List<ItemReport>> e : reports.entrySet()) {
            for (ItemReport r : e.getValue()) {
                if (r.kind == ItemReport.Kind.FAILED && firstFailed == null && this.items.containsKey(e.getKey())) {
                    firstFailed = this.items.get(e.getKey()).label;
                }
            }
        }
        // Sub-items first, so a parent's entry can count them.
        List<ItemRow> order = new ArrayList<>();
        for (ItemRow row : this.items.values()) {
            if (row.parentKey != null) {
                order.add(row);
            }
        }
        for (ItemRow row : this.items.values()) {
            if (row.parentKey == null) {
                order.add(row);
            }
        }
        for (ItemRow row : order) {
            if (this.ended.contains(row.itemKey) && !reports.containsKey(row.itemKey)) {
                continue;
            }
            boolean in = wentIn.contains(row.itemKey);
            List<Integer> mine = positions.getOrDefault(row.itemKey, Collections.<Integer>emptyList());
            List<ItemReport> said = reports.getOrDefault(row.itemKey, Collections.<ItemReport>emptyList());
            if (!in && mine.isEmpty() && said.isEmpty() && !(row.parentKey == null && this.hasChildEntry(row, o.index))) {
                continue;
            }
            ItemRow.StepEntry entry = new ItemRow.StepEntry(o.index, step.key, step.task, ItemRow.StepEntry.DONE);
            entry.se = o.stepExecutionId;
            if (o.index == this.sourceIndex) {
                entry.idc = this.identity;
            }
            if (step.role == ItemRole.FAN_OUT && this.subColumn != null) {
                entry.subc = this.subColumn;
            }
            long ms = 0;
            int made = 0;
            String failure = null;
            for (ItemReport r : said) {
                switch (r.kind) {
                    case TOOK:
                        ms += r.millis;
                        break;
                    case FILE:
                        made++;
                        this.file(row, step, o.stepExecutionId, r);
                        break;
                    case FAILED:
                        failure = failure == null ? r.reason : failure;
                        break;
                    default:
                        break;
                }
            }
            entry.ms = said.stream().anyMatch(r -> r.kind == ItemReport.Kind.TOOK) ? ms : null;
            entry.files = made == 0 ? null : made;
            if (!mine.isEmpty()) {
                entry.ds = out == null ? this.datasetOfInput(row, o) : ds;
                entry.rows = new ArrayList<>(mine.subList(0, Math.min(MAX_POSITIONS, mine.size())));
                entry.n = mine.size();
            }
            if (row.parentKey == null && this.hasChildEntry(row, o.index)) {
                this.parentEntry(row, entry, o.index);
            }
            if (!o.completed) {
                if (failure != null) {
                    entry.status = ItemRow.StepEntry.FAILED;
                    entry.note = failure;
                    row.fail(o.index, step.key, failure, now);
                } else if (!row.finished()) {
                    entry.status = ItemRow.StepEntry.FAILED;
                    String why = anyReportedFailure && firstFailed != null
                        ? String.format("Stopped: %s failed at %s, and the step stops at its first failure.", firstFailed, step.name)
                        : String.format("%s failed: %s", step.name, o.error == null ? "the step failed" : o.error);
                    entry.note = why;
                    row.fail(o.index, step.key, why, now);
                } else {
                    entry.status = ItemRow.StepEntry.NOT_RUN;
                }
                this.ended.add(row.itemKey);
            } else if (failure != null) {
                entry.status = ItemRow.StepEntry.FAILED;
                entry.note = failure;
                row.fail(o.index, step.key, failure, now);
            } else if (step.role == ItemRole.SHARED) {
                entry.status = ItemRow.StepEntry.SHARED;
                entry.sharedBy = Math.max(1, sharedBy);
                entry.ds = ds;
                entry.note = String.format("Shared by %d item%s", entry.sharedBy, entry.sharedBy == 1 ? "" : "s");
            } else if (mine.isEmpty() && !(row.parentKey == null && this.hasChildEntry(row, o.index))) {
                if (out != null && !this.followable(out)) {
                    // The step's rows no longer name items (an aggregate's): its output is every item's.
                    entry.status = ItemRow.StepEntry.SHARED;
                    entry.sharedBy = Math.max(1, (int) this.items.values().stream().filter(r -> r.parentKey == null && !this.ended.contains(r.itemKey)).count());
                    entry.ds = ds;
                    entry.note = String.format("Shared by %d item%s", entry.sharedBy, entry.sharedBy == 1 ? "" : "s");
                } else if (out == null && inMap == null) {
                    entry.note = null;
                } else {
                    entry.status = ItemRow.StepEntry.LEFT_OUT;
                    entry.note = "Not in this step's output: the item ends here.";
                    this.ended.add(row.itemKey);
                    if (!row.finished()) {
                        row.status = ItemRow.DONE;
                        row.endedAt = now;
                    }
                }
            }
            if (entry.note == null) {
                entry.note = this.note(step, row, entry, said);
            }
            row.put(entry);
            this.touched.add(row.itemKey);
            if (row.parentKey != null) {
                this.touched.add(row.parentKey);
            }
            row.fileCount += made;
            if (row.startedAt == null) {
                row.startedAt = now;
            }
            this.dirty.add(row.itemKey);
        }
        if (o.completed && out != null && this.positional) {
            // With no key column, a step that does not keep the rows in order loses them.
            if (outMap.isEmpty() && !out.isEmpty()) {
                logger.info("Run {}: step <{}> does not keep its rows in order; items are not followed past it.", this.jobQueueId, step.key);
            }
        }
    }

    /** Whether a parent has sub-items that took part in this step. */
    private boolean hasChildEntry(ItemRow parent, int index) {
        for (ItemRow row : this.items.values()) {
            if (parent.itemKey.equals(row.parentKey) && row.step(index) != null) {
                return true;
            }
        }
        return false;
    }

    /** A parent's entry for a step its sub-items took part in: their counts, rows and files. */
    private void parentEntry(ItemRow parent, ItemRow.StepEntry entry, int index) {
        int total = 0;
        int failed = 0;
        int rows = 0;
        int made = 0;
        long ms = 0;
        boolean timed = false;
        for (ItemRow row : this.items.values()) {
            if (!parent.itemKey.equals(row.parentKey)) {
                continue;
            }
            ItemRow.StepEntry child = row.step(index);
            if (child == null) {
                continue;
            }
            total++;
            failed += ItemRow.StepEntry.FAILED.equals(child.status) ? 1 : 0;
            rows += child.n == null ? 0 : child.n;
            made += child.files == null ? 0 : child.files;
            if (child.ms != null) {
                ms += child.ms;
                timed = true;
            }
        }
        if (total == 0) {
            return;
        }
        entry.n = rows;
        entry.files = made == 0 ? null : made;
        // The document's own time (a split's) already holds its pages'; otherwise the pages' added up.
        entry.ms = entry.ms != null ? entry.ms : timed ? Long.valueOf(ms) : null;
        entry.note = String.format("%d %s%s", total, this.subWord(total), failed == 0 ? "" : String.format(": %d done, %d failed", total - failed, failed));
    }

    private String subWord(int n) {
        String word = "page".equals(this.subColumn) ? "page" : "sub-item";
        return n == 1 ? word : word + "s";
    }

    /** A step's line for an item: what it did with it. */
    private String note(StepInfo step, ItemRow row, ItemRow.StepEntry entry, List<ItemReport> said) {
        if (ItemRow.StepEntry.LEFT_OUT.equals(entry.status) || ItemRow.StepEntry.SHARED.equals(entry.status)) {
            return entry.note;
        }
        List<String> parts = new ArrayList<>();
        if (entry.n != null) {
            parts.add(entry.n == 1 ? "1 row" : String.format("%,d rows", entry.n));
        }
        if (entry.files != null) {
            parts.add(entry.files == 1 ? "1 file" : String.format("%,d files", entry.files));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** The run_dataset a passed-on step's rows are in: the item's entry of the step it read. */
    private Long datasetOfInput(ItemRow row, Outcome o) {
        Long found = null;
        for (ItemRow.StepEntry entry : row.steps) {
            if (entry.i < o.index && entry.ds != null) {
                found = entry.ds;
            }
        }
        return found;
    }

    /** The items, at the step that names them. False when there are too many (the run then keeps none). */
    private boolean create(StepInfo step, Outcome o, Map<Integer, String> inMap, List<Rows> out, Instant now) {
        if (this.identity == null) {
            this.beforeStep(o.index, o.inputKey, null);
        }
        if (this.restart && !this.retired) {
            this.store.retireBefore(this.jobQueueId, this.attempt);
            this.retired = true;
        }
        Map<Integer, String> input = new HashMap<>();
        if (step.role != ItemRole.SOURCE && o.inputKey != null) {
            List<Rows> in = this.read(o.inputKey);
            if (in == ROWS_TOO_MANY) {
                this.stop(String.format("step <%s> reads more than %,d rows", step.key, MAX_SCAN_ROWS));
                return false;
            }
            for (Rows r : in) {
                String value = this.positional ? "row-" + (r.position + 1) : text(r.row.get(this.identity));
                if (value != null) {
                    if (this.make(step, value, r.row, o, now) == null) {
                        return false;
                    }
                    input.put(r.position, value);
                }
            }
            this.mappings.put(o.inputKey, input);
        }
        if (step.role == ItemRole.SOURCE && out != null) {
            for (Rows r : out) {
                String value = text(r.row.get(this.identity));
                if (value != null && this.make(step, value, r.row, o, now) == null) {
                    return false;
                }
            }
        } else if (!this.positional && out != null) {
            for (Rows r : out) {
                String value = text(r.row.get(this.identity));
                if (value != null && !this.items.containsKey(value) && !input.containsValue(value)) {
                    if (this.make(step, value, r.row, o, now) == null) {
                        return false;
                    }
                }
            }
        }
        if (!this.positional) {
            for (ItemReport report : o.reports) {
                String value = report.row == null ? null : text(report.row.get(this.identity));
                if (value != null && !this.items.containsKey(value) && !(step.role == ItemRole.FAN_OUT && this.isSubRow(step, report.row))) {
                    if (this.make(step, value, report.row, o, now) == null) {
                        return false;
                    }
                }
            }
        }
        if (this.items.isEmpty()) {
            return true;
        }
        this.engine.audit(String.format("Items: %d item%s, named by %s at step %d <%s>.", this.items.size(), this.items.size() == 1 ? "" : "s",
            this.positional ? "their row number" : this.identity, step.index + 1, step.key));
        return true;
    }

    private boolean isSubRow(StepInfo step, Map<String, Object> row) {
        return step.subKey != null && row != null && row.get(step.subKey) != null;
    }

    /** A new top-level item; null when the run has too many (it then keeps none). */
    private ItemRow make(StepInfo step, String key, Map<String, Object> row, Outcome o, Instant now) {
        ItemRow existing = this.items.get(key);
        if (existing != null) {
            return existing;
        }
        if (this.items.size() >= MAX_ITEMS) {
            this.stop(String.format("it has more than %,d items, the most a run keeps", MAX_ITEMS));
            return null;
        }
        ItemRow item = new ItemRow();
        item.jobQueueId = this.jobQueueId;
        item.attempt = this.attempt;
        item.itemKey = key.length() > 1100 ? key.substring(0, 1100) : key;
        ItemRow earlier = this.before.get(item.itemKey);
        item.position = earlier != null ? earlier.position : ++this.topCount;
        item.itemRef = String.valueOf(item.position);
        item.label = labelOf(this.positional ? null : this.identity, key, row, item.position);
        item.startedAt = now;
        this.items.put(item.itemKey, item);
        this.alias.put(key, item.itemKey);
        this.dirty.add(item.itemKey);
        // Its input object, when its row names one.
        String objectKey = row == null ? null : text(row.get("key"));
        String bucket = row == null ? null : text(row.get("bucket"));
        bucket = bucket != null ? bucket : step.bucket != null ? step.bucket : o.runBucket;
        if (objectKey != null && bucket != null && ("key".equals(this.identity) || step.readsFiles)) {
            ItemRow.FileRow f = new ItemRow.FileRow();
            f.jobQueueId = this.jobQueueId;
            f.attempt = this.attempt;
            f.itemKey = item.itemKey;
            f.stepIndex = step.index;
            f.stepKey = step.key;
            f.role = ItemRow.FileRow.INPUT;
            f.fileName = FileNames.last(objectKey);
            f.bucketAlias = bucket;
            f.objectKey = objectKey;
            Object size = row.get("size");
            f.byteCount = size instanceof Number ? ((Number) size).longValue() : null;
            this.files.add(f);
            item.fileCount++;
        }
        return item;
    }

    /** A fan-out step's row as a sub-item of its item; its key, or null when its row names no item. */
    private String subItem(StepInfo step, Map<String, Object> row, Instant now) {
        if (step.subKey == null || row == null) {
            return null;
        }
        if (this.subColumn == null) {
            this.subColumn = step.subKey;
        }
        String sub = text(row.get(step.subKey));
        String parent = this.identity == null || this.positional ? null : this.alias.get(text(row.get(this.identity)));
        if (sub == null || parent == null) {
            return parent;
        }
        String key = parent + "#" + sub;
        if (this.items.containsKey(key) || (this.before.containsKey(key) && !this.reruns(key))) {
            // This attempt's own, or an earlier one it does not rerun (a retry of another page): named, not made again.
            return key;
        }
        ItemRow top = this.items.get(parent);
        if (top == null) {
            return parent;
        }
        if (this.subTotal >= MAX_SUB_ITEMS) {
            return parent;
        }
        ItemRow was = this.before.get(key);
        ItemRow item = new ItemRow();
        item.jobQueueId = this.jobQueueId;
        item.attempt = this.attempt;
        item.itemKey = key.length() > 1100 ? key.substring(0, 1100) : key;
        item.parentKey = parent;
        item.position = was != null ? was.position : this.subCounts.merge(parent, 1, Integer::sum);
        item.itemRef = top.itemRef + "." + item.position;
        item.label = "page".equals(step.subKey) ? "Page " + sub : step.subKey.replace('_', ' ') + " " + sub;
        item.startedAt = now;
        if (was == null) {
            this.subTotal++;
        }
        // The steps its document went through before the split are its own story too: they point at the document's rows.
        for (ItemRow.StepEntry entry : top.steps) {
            if (entry.i < step.index) {
                item.put(entry.copy());
            }
        }
        this.items.put(item.itemKey, item);
        this.dirty.add(item.itemKey);
        return item.itemKey;
    }

    /** A step's output rows to items. */
    private Map<Integer, String> mapOutput(StepInfo step, Map<Integer, String> inMap, List<Rows> out, Map<String, List<ItemReport>> reports,
        Instant now) {
        Map<Integer, String> map = new HashMap<>();
        if (step.role == ItemRole.FAN_OUT) {
            for (Rows r : out) {
                String key = this.subItem(step, r.row, now);
                if (key != null) {
                    map.put(r.position, key);
                }
            }
            return map;
        }
        if (step.role == ItemRole.EACH && inMap != null) {
            // A per-item step keeps its rows in order and leaves out only the rows it said failed (skip).
            int inSize = inMap.isEmpty() ? 0 : Collections.max(inMap.keySet()) + 1;
            Set<String> failed = new HashSet<>();
            for (Map.Entry<String, List<ItemReport>> e : reports.entrySet()) {
                if (e.getValue().stream().anyMatch(r -> r.kind == ItemReport.Kind.FAILED)) {
                    failed.add(e.getKey());
                }
            }
            List<Integer> kept = new ArrayList<>();
            if (out.size() == inSize) {
                for (int p = 0; p < inSize; p++) {
                    kept.add(p);
                }
            } else {
                for (int p = 0; p < inSize; p++) {
                    if (!failed.contains(inMap.get(p))) {
                        kept.add(p);
                    }
                }
            }
            if (kept.size() == out.size()) {
                for (Rows r : out) {
                    String key = inMap.get(kept.get(r.position));
                    if (key != null) {
                        map.put(r.position, key);
                        this.follow(r.row, key);
                    }
                }
                return map;
            }
        }
        if (this.positional) {
            if (inMap != null && step.role == ItemRole.PASS && !"sort".equals(step.task) && !inMap.isEmpty()
                && out.size() == Collections.max(inMap.keySet()) + 1) {
                return new HashMap<>(inMap);
            }
            return map;
        }
        for (Rows r : out) {
            String key = this.keyOf(r.row);
            if (key != null) {
                map.put(r.position, key);
            }
        }
        return map;
    }

    /** After a per-item step: the row's identity value (it may have rewritten it) names its item from now on. */
    private void follow(Map<String, Object> row, String key) {
        if (this.identity == null || this.positional) {
            return;
        }
        String value = text(row.get(this.identity));
        ItemRow item = this.items.get(key);
        if (value != null && item != null && item.parentKey == null) {
            this.alias.put(value, key);
        }
    }

    /** Whether a step's rows can still be told apart by item. */
    private boolean followable(List<Rows> out) {
        if (this.positional) {
            return false;
        }
        for (Rows r : out) {
            if (r.row.get(this.identity) != null) {
                return true;
            }
        }
        return out.isEmpty();
    }

    private void file(ItemRow row, StepInfo step, long stepExecutionId, ItemReport report) {
        ItemRow.FileRow f = new ItemRow.FileRow();
        f.jobQueueId = this.jobQueueId;
        f.attempt = this.attempt;
        f.itemKey = row.itemKey;
        f.stepIndex = step.index;
        f.stepKey = step.key;
        f.role = ItemRow.FileRow.OUTPUT;
        f.stepExecutionId = stepExecutionId;
        f.fileName = report.fileName;
        f.byteCount = report.bytes <= 0 ? null : report.bytes;
        this.files.add(f);
    }

    /** The run keeps no items from here: why, said once in its log. */
    private void stop(String why) {
        if (this.off != null) {
            return;
        }
        this.off = why;
        this.items.clear();
        this.dirty.clear();
        this.files.clear();
        this.engine.audit(String.format("Items: this run's items are not kept: %s. Its steps and outputs show as usual.", why));
    }

    /**
     * The attempt ended: {@code completed}, else failed ({@code why}) or interrupted. Items still running are done or not
     * finished, parents count their sub-items, and each top-level item this attempt finished is announced
     * (run.item.completed). {@code paused}: it waits for an approval; nothing ends.
     */
    public void finish(boolean completed, boolean interrupted, boolean paused, String why) {
        if (this.off != null) {
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!paused) {
            for (ItemRow row : this.items.values()) {
                if (!row.finished() && this.fromIndex >= 0 && !this.touched.contains(row.itemKey) && !this.before.isEmpty()
                    && this.before.containsKey(row.itemKey)) {
                    // ZT-I10: a retried item whose rows no step of the retry read: not done -- it keeps its earlier outcome.
                    ItemRow was = this.before.get(row.itemKey);
                    row.status = was.status;
                    row.failedStepIndex = was.failedStepIndex;
                    row.failedStepKey = was.failedStepKey;
                    row.reason = ItemRow.FAILED.equals(was.status)
                        ? ItemRow.cut("Not retried: no step of the retry read this item's rows. Earlier: " + was.reason, 2000) : was.reason;
                    row.endedAt = now;
                    this.dirty.add(row.itemKey);
                    continue;
                }
                if (!row.finished()) {
                    if (completed) {
                        row.status = ItemRow.DONE;
                        row.endedAt = now;
                    } else {
                        int at = row.steps.isEmpty() ? Math.max(0, this.sourceIndex) : row.steps.get(row.steps.size() - 1).i;
                        row.fail(at, this.steps.get(Math.min(at, this.steps.size() - 1)).key, interrupted
                            ? "The run was stopped before this item finished." : "Not finished: " + (why == null ? "the run failed." : why), now);
                    }
                    this.dirty.add(row.itemKey);
                }
                long ms = 0;
                boolean timed = false;
                for (ItemRow.StepEntry entry : row.steps) {
                    if (entry.ms != null) {
                        ms += entry.ms;
                        timed = true;
                    }
                }
                row.durationMs = timed ? ms : row.startedAt == null || row.endedAt == null ? null
                    : Duration.between(row.startedAt, row.endedAt).toMillis();
            }
        }
        this.countChildren();
        this.flush();
        if (paused) {
            return;
        }
        for (ItemRow row : this.items.values()) {
            if (row.parentKey == null && row.runItemId != null && row.finished()) {
                try {
                    this.store.journalCompleted(row.runItemId);
                } catch (RuntimeException unjournaled) {
                    logger.warn("Run {}: run.item.completed for item {} could not be journaled: {}", this.jobQueueId, row.itemRef,
                        unjournaled.getMessage());
                }
            }
        }
    }

    /** Each parent's sub-item counts, from this attempt's sub-items and the earlier ones it did not rerun. */
    private void countChildren() {
        Map<String, ItemRow> latest = new LinkedHashMap<>(this.before);
        latest.putAll(this.items);
        Map<String, int[]> counts = new HashMap<>();
        for (ItemRow row : latest.values()) {
            if (row.parentKey != null) {
                int[] c = counts.computeIfAbsent(row.parentKey, k -> new int[2]);
                c[0]++;
                c[1] += ItemRow.FAILED.equals(row.status) ? 1 : 0;
            }
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            ItemRow parent = this.items.get(e.getKey());
            if (parent == null) {
                parent = this.before.get(e.getKey());
                if (parent == null) {
                    continue;
                }
                // A document only some of whose pages were retried: its own row, recounted in place.
                if (parent.childCount != e.getValue()[0] || parent.childFailed != e.getValue()[1]) {
                    parent.childCount = e.getValue()[0];
                    parent.childFailed = e.getValue()[1];
                    this.items.put(parent.itemKey, parent);
                    this.dirty.add(parent.itemKey);
                }
                continue;
            }
            parent.childCount = e.getValue()[0];
            parent.childFailed = e.getValue()[1];
            this.dirty.add(parent.itemKey);
        }
    }

    /** The run's items in a sentence for its status line and its mail; null for a run with none. */
    public String summary() {
        if (this.off != null) {
            return null;
        }
        List<ItemRow> rows = this.store.current(this.jobQueueId);
        int total = 0;
        int done = 0;
        Map<String, List<String>> failedAt = new LinkedHashMap<>();
        for (ItemRow row : rows) {
            if (row.parentKey != null) {
                continue;
            }
            total++;
            if (ItemRow.FAILED.equals(row.status)) {
                String at = row.failedStepIndex == null ? "?" : this.steps.get(Math.min(row.failedStepIndex, this.steps.size() - 1)).name;
                failedAt.computeIfAbsent(at, k -> new ArrayList<>()).add(row.label);
            } else if (row.childFailed > 0) {
                failedAt.computeIfAbsent(String.format("%d %s of %s", row.childFailed, this.subWord(row.childFailed), row.label),
                    k -> new ArrayList<>());
            } else if (ItemRow.DONE.equals(row.status)) {
                done++;
            }
        }
        if (total == 0) {
            return null;
        }
        StringBuilder line = new StringBuilder(String.format("Items: %d of %d done", done, total));
        int failed = total - done;
        if (failed > 0) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : failedAt.entrySet()) {
                if (e.getValue().isEmpty()) {
                    parts.add(e.getKey() + " failed");
                } else {
                    List<String> names = e.getValue();
                    parts.add(String.format("%d failed at %s: %s%s", names.size(), e.getKey(),
                        String.join(", ", names.subList(0, Math.min(5, names.size()))), names.size() > 5 ? String.format(" and %d more", names.size() - 5) : ""));
                }
            }
            line.append("; ").append(String.join("; ", parts));
        }
        return line.append('.').toString();
    }

    /** The changed items and the new files, written. */
    private void flush() {
        if (this.dirty.isEmpty() && this.files.isEmpty()) {
            return;
        }
        List<ItemRow> rows = new ArrayList<>();
        for (String key : this.dirty) {
            ItemRow row = this.items.get(key);
            if (row != null) {
                rows.add(row);
            }
        }
        try {
            this.store.save(rows);
            this.dirty.clear();
            if (!this.files.isEmpty()) {
                this.store.addFiles(new ArrayList<>(this.files));
                this.files.clear();
            }
        } catch (RuntimeException unsaved) {
            logger.warn("Run {} attempt {}: its items could not be saved: {}", this.jobQueueId, this.attempt, unsaved.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------- rows

    private static final List<Rows> ROWS_TOO_MANY = Collections.unmodifiableList(new ArrayList<>());

    /** One row of a dataset with its position. */
    private static final class Rows {
        final int position;
        final Map<String, Object> row;

        Rows(int position, Map<String, Object> row) {
            this.position = position;
            this.row = row;
        }
    }

    /** A dataset's rows, only the columns that name items (and a fan-out's sub key), with their positions. */
    private List<Rows> read(String datasetKey) {
        List<Rows> out = new ArrayList<>();
        Set<String> wanted = new HashSet<>(Arrays.asList("key", "bucket", "size", "file_name"));
        if (this.identity != null) {
            wanted.add(this.identity);
        }
        for (StepInfo step : this.steps) {
            if (step.subKey != null) {
                wanted.add(step.subKey);
            }
            if (step.identity != null) {
                wanted.add(step.identity);
            }
        }
        wanted.addAll(CANDIDATES);
        int p = 0;
        try (RowSource rows = this.engine.open(datasetKey)) {
            if (rows.size() > MAX_SCAN_ROWS) {
                return ROWS_TOO_MANY;
            }
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                for (Map<String, Object> row : batch) {
                    Map<String, Object> slim = new HashMap<>();
                    for (Map.Entry<String, Object> cell : row.entrySet()) {
                        if (wanted.contains(cell.getKey()) || cell.getKey().toLowerCase(Locale.ROOT).endsWith("_id")) {
                            slim.put(cell.getKey(), cell.getValue());
                        }
                    }
                    out.add(new Rows(p++, slim));
                }
            }
        } catch (Exception unread) {
            throw new IllegalStateException("the dataset could not be read back: " + unread.getMessage(), unread);
        }
        return out;
    }

    /** What the console calls an item: a file's name, a document's file name, else its key. */
    static String labelOf(String identity, String key, Map<String, Object> row, int position) {
        if (identity == null) {
            return "Row " + position;
        }
        if ("extraction_id".equals(identity) && row != null && text(row.get("file_name")) != null) {
            return text(row.get("file_name"));
        }
        if (key.contains("/")) {
            return FileNames.last(key);
        }
        return key;
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = Values.text(value);
        if (text == null) {
            text = String.valueOf(value);
        }
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** File-name helpers. */
    static final class FileNames {
        private FileNames() {
        }

        static String last(String key) {
            String k = key == null ? "" : key;
            String name = k.substring(k.lastIndexOf('/') + 1);
            return name.isEmpty() ? k : name;
        }
    }
}
