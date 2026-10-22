package process.pipeline.items;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@link RunItemStore} in memory, for the engine's and the read side's tests: rows copied in and out, as a database would. */
public class InMemoryRunItemStore implements RunItemStore {

    public final List<ItemRow> rows = new ArrayList<>();
    public final List<ItemRow.FileRow> files = new ArrayList<>();
    public final List<Long> journaled = new ArrayList<>();
    private long nextId = 1;
    private long nextFile = 1;

    @Override
    public synchronized void save(List<ItemRow> saved) {
        for (ItemRow row : saved) {
            ItemRow found = null;
            for (ItemRow r : this.rows) {
                if (r.jobQueueId == row.jobQueueId && r.itemKey.equals(row.itemKey) && r.attempt == row.attempt) {
                    found = r;
                }
            }
            if (found == null) {
                found = new ItemRow();
                found.runItemId = this.nextId++;
                this.rows.add(found);
            }
            copy(row, found);
            found.current = true;
            row.runItemId = found.runItemId;
            for (ItemRow r : this.rows) {
                if (r.jobQueueId == row.jobQueueId && r.itemKey.equals(row.itemKey) && r.attempt < row.attempt) {
                    r.current = false;
                }
            }
        }
    }

    private static void copy(ItemRow from, ItemRow to) {
        to.jobQueueId = from.jobQueueId;
        to.attempt = from.attempt;
        to.itemKey = from.itemKey;
        to.itemRef = from.itemRef;
        to.parentKey = from.parentKey;
        to.label = from.label;
        to.position = from.position;
        to.status = from.status;
        to.failedStepIndex = from.failedStepIndex;
        to.failedStepKey = from.failedStepKey;
        to.reason = from.reason;
        to.steps = JdbcRunItemStore.entries(JdbcRunItemStore.json(from.steps));
        to.childCount = from.childCount;
        to.childFailed = from.childFailed;
        to.fileCount = from.fileCount;
        to.startedAt = from.startedAt;
        to.endedAt = from.endedAt;
        to.durationMs = from.durationMs;
    }

    private static ItemRow out(ItemRow r) {
        ItemRow c = new ItemRow();
        copy(r, c);
        c.runItemId = r.runItemId;
        c.current = r.current;
        return c;
    }

    @Override
    public synchronized void retireBefore(long jobQueueId, int attempt) {
        this.rows.stream().filter(r -> r.jobQueueId == jobQueueId && r.attempt < attempt).forEach(r -> r.current = false);
    }

    @Override
    public synchronized void addFiles(List<ItemRow.FileRow> added) {
        for (ItemRow.FileRow f : added) {
            f.runItemFileId = this.nextFile++;
            this.files.add(f);
        }
    }

    private static final Comparator<ItemRow> ORDER = Comparator.comparing((ItemRow r) -> r.parentKey == null ? "" : "~" + r.parentKey)
        .thenComparingInt(r -> r.position);

    @Override
    public synchronized List<ItemRow> current(long jobQueueId) {
        return this.rows.stream().filter(r -> r.jobQueueId == jobQueueId && r.current).sorted(ORDER).map(InMemoryRunItemStore::out)
            .collect(Collectors.toList());
    }

    @Override
    public synchronized List<ItemRow> ofAttempt(long jobQueueId, int attempt) {
        return this.rows.stream().filter(r -> r.jobQueueId == jobQueueId && r.attempt == attempt).sorted(ORDER).map(InMemoryRunItemStore::out)
            .collect(Collectors.toList());
    }

    @Override
    public synchronized List<ItemRow> versions(long jobQueueId, String itemKey) {
        return this.rows.stream().filter(r -> r.jobQueueId == jobQueueId && r.itemKey.equals(itemKey))
            .sorted(Comparator.comparingInt((ItemRow r) -> r.attempt).reversed()).map(InMemoryRunItemStore::out).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<ItemRow> byId(long runItemId) {
        return this.rows.stream().filter(r -> r.runItemId == runItemId).findFirst().map(InMemoryRunItemStore::out);
    }

    private List<ItemRow.FileRow> matching(long jobQueueId, String itemKey, int attempt, String stepKey, String search) {
        return this.files.stream().filter(f -> f.jobQueueId == jobQueueId && f.itemKey.equals(itemKey) && f.attempt == attempt
            && (stepKey == null || stepKey.equals(f.stepKey))
            && (search == null || search.isEmpty() || f.fileName.toLowerCase().contains(search.toLowerCase()))).collect(Collectors.toList());
    }

    @Override
    public synchronized List<ItemRow.FileRow> files(long jobQueueId, String itemKey, int attempt, String stepKey, String search, int offset,
        int limit) {
        List<ItemRow.FileRow> all = this.matching(jobQueueId, itemKey, attempt, stepKey, search);
        return all.subList(Math.min(offset, all.size()), Math.min(all.size(), offset + limit));
    }

    @Override
    public synchronized int countFiles(long jobQueueId, String itemKey, int attempt, String stepKey, String search) {
        return this.matching(jobQueueId, itemKey, attempt, stepKey, search).size();
    }

    private List<ItemRow.FileRow> family(long jobQueueId, String parentKey, String stepKey, String search) {
        Map<String, ItemRow> members = new LinkedHashMap<>();
        for (ItemRow r : this.rows) {
            if (r.jobQueueId == jobQueueId && r.current && (r.itemKey.equals(parentKey) || parentKey.equals(r.parentKey))) {
                members.put(r.itemKey + "@" + r.attempt, r);
            }
        }
        return this.files.stream().filter(f -> f.jobQueueId == jobQueueId && members.containsKey(f.itemKey + "@" + f.attempt)
            && (stepKey == null || stepKey.equals(f.stepKey))
            && (search == null || search.isEmpty() || f.fileName.toLowerCase().contains(search.toLowerCase())))
            .sorted(Comparator.<ItemRow.FileRow>comparingInt(f -> f.stepIndex)
                .thenComparing(f -> members.get(f.itemKey + "@" + f.attempt).parentKey != null)
                .thenComparingInt(f -> members.get(f.itemKey + "@" + f.attempt).position))
            .collect(Collectors.toList());
    }

    @Override
    public synchronized List<ItemRow.FileRow> familyFiles(long jobQueueId, String parentKey, String stepKey, String search, int offset, int limit) {
        List<ItemRow.FileRow> all = this.family(jobQueueId, parentKey, stepKey, search);
        return all.subList(Math.min(offset, all.size()), Math.min(all.size(), offset + limit));
    }

    @Override
    public synchronized int countFamilyFiles(long jobQueueId, String parentKey, String stepKey, String search) {
        return this.family(jobQueueId, parentKey, stepKey, search).size();
    }

    @Override
    public synchronized Optional<ItemRow.FileRow> file(long runItemFileId) {
        return this.files.stream().filter(f -> f.runItemFileId == runItemFileId).findFirst();
    }

    @Override
    public synchronized Map<Long, int[]> counts(Collection<Long> jobQueueIds) {
        Map<Long, int[]> out = new LinkedHashMap<>();
        for (ItemRow r : this.rows) {
            if (!r.current || r.parentKey != null || !jobQueueIds.contains(r.jobQueueId)) {
                continue;
            }
            int[] c = out.computeIfAbsent(r.jobQueueId, k -> new int[4]);
            c[0]++;
            if (ItemRow.RUNNING.equals(r.status)) {
                c[3]++;
            } else if (ItemRow.FAILED.equals(r.status) || r.childFailed > 0) {
                c[2]++;
            } else {
                c[1]++;
            }
        }
        return out;
    }

    @Override
    public List<Attention> attention(Instant since, int limit) {
        return new ArrayList<>();
    }

    @Override
    public synchronized void journalCompleted(long runItemId) {
        this.journaled.add(runItemId);
    }

    /** The current row of an item, by key. */
    public synchronized ItemRow currentOf(long jobQueueId, String itemKey) {
        return this.rows.stream().filter(r -> r.jobQueueId == jobQueueId && r.current && r.itemKey.equals(itemKey)).findFirst()
            .map(InMemoryRunItemStore::out).orElse(null);
    }
}
