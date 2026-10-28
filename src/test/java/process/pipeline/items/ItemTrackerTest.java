package process.pipeline.items;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.InMemoryDatasetStore;
import process.pipeline.data.RowSource;
import process.pipeline.registry.ItemRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-420: what ItemTracker makes of a run's steps, step by step -- a run with more items than a run keeps keeps none and
 * says so; a combining step's output is shared by the items that went in; a filter that leaves an item out ends it there;
 * a row's call key is its item's, the same whatever position it has, and its row number when it belongs to no item.
 */
class ItemTrackerTest {

    private static final long RUN = 5001L;

    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private final List<String> audit = new ArrayList<>();

    private final ItemTracker.Engine engine = new ItemTracker.Engine() {
        @Override
        public RowSource open(String datasetKey) throws Exception {
            return datasets.open(datasetKey, 100);
        }

        @Override
        public String storageKeyOf(long runDatasetId) {
            return "ds-" + runDatasetId;
        }

        @Override
        public void audit(String line) {
            audit.add(line);
        }
    };

    private static ItemTracker.StepInfo step(int index, String key, ItemRole role, String identity) {
        return new ItemTracker.StepInfo(index, key, key, key, role, identity, null, null, false);
    }

    private String write(String key, List<Map<String, Object>> rows) throws Exception {
        this.datasets.write(key, new Dataset(rows.isEmpty() ? Collections.<String>emptyList() : new ArrayList<>(rows.get(0).keySet()), rows));
        return key;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private ItemTracker.Outcome done(int index, String in, String out, long ds) {
        ItemTracker.Outcome o = new ItemTracker.Outcome();
        o.index = index;
        o.stepExecutionId = 100 + index;
        o.inputKey = in;
        o.outputKey = out;
        o.outputDatasetId = ds;
        o.completed = true;
        return o;
    }

    @Test
    void aRunWithMoreItemsThanARunKeepsKeepsNoneAndSaysSo() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= ItemTracker.MAX_ITEMS; i++) {
            rows.add(row("key", "in/" + i + ".m4a"));
        }
        String listed = this.write("list", rows);
        List<ItemTracker.StepInfo> steps = Arrays.asList(step(0, "read", ItemRole.SOURCE, "key"), step(1, "ask", ItemRole.EACH, null));
        ItemTracker tracker = ItemTracker.open(RUN, 1, this.store, this.engine, steps, -1, 0, null);
        tracker.beforeStep(0, null, Collections.emptyList());
        tracker.afterStep(this.done(0, null, listed, 1));

        assertThat(tracker.off()).isTrue();
        assertThat(this.store.rows).isEmpty();
        assertThat(this.audit).containsExactly("Items: this run's items are not kept: it has more than 1,000 items, the most a run keeps. "
            + "Its steps and outputs show as usual.");
        assertThat(tracker.tagOf(rows.get(3), 3)).as("no items: rows keep their numbers").isEqualTo("4");
    }

    @Test
    void aCombiningStepsOutputIsSharedByTheItemsThatWentInAndAFilterEndsAnItem() throws Exception {
        String listed = this.write("list", Arrays.asList(row("key", "in/a.m4a", "size", 10), row("key", "in/b.m4a", "size", 20),
            row("key", "in/c.m4a", "size", 30)));
        String asked = this.write("ask", Arrays.asList(row("key", "in/a.m4a", "risk", "High"), row("key", "in/b.m4a", "risk", "Low"),
            row("key", "in/c.m4a", "risk", "High")));
        String kept = this.write("high", Arrays.asList(row("key", "in/a.m4a", "risk", "High"), row("key", "in/c.m4a", "risk", "High")));
        String total = this.write("total", Collections.singletonList(row("risk", "High", "count", 2)));
        List<ItemTracker.StepInfo> steps = Arrays.asList(step(0, "read", ItemRole.SOURCE, "key"), step(1, "ask", ItemRole.EACH, null),
            step(2, "high", ItemRole.PASS, null), step(3, "total", ItemRole.SHARED, null));
        ItemTracker tracker = ItemTracker.open(RUN, 1, this.store, this.engine, steps, -1, 0, null);
        tracker.beforeStep(0, null, Collections.emptyList());
        tracker.afterStep(this.done(0, null, listed, 1));
        tracker.afterStep(this.done(1, listed, asked, 2));
        tracker.afterStep(this.done(2, asked, kept, 3));
        tracker.afterStep(this.done(3, kept, total, 4));
        tracker.finish(true, false, false, null);

        ItemRow b = this.store.currentOf(RUN, "in/b.m4a");
        assertThat(b.status).isEqualTo("done");
        assertThat(b.step(2).status).isEqualTo("left_out");
        assertThat(b.step(2).note).isEqualTo("Not in this step's output: the item ends here.");
        assertThat(b.step(3)).as("an item that ended is not in the later steps").isNull();
        ItemRow a = this.store.currentOf(RUN, "in/a.m4a");
        assertThat(a.step(3).status).isEqualTo("shared");
        assertThat(a.step(3).sharedBy).isEqualTo(2);
        assertThat(a.step(3).note).isEqualTo("Shared by 2 items");
        assertThat(a.step(3).rows).as("a shared output never pretends to be one item's").isNull();
        assertThat(a.step(3).ds).isEqualTo(4L);
        assertThat(this.store.files).extracting(f -> f.role + " " + f.fileName).as("no bucket named: no input file")
            .isEmpty();
        assertThat(tracker.summary()).isEqualTo("Items: 3 of 3 done.");
    }

    /**
     * ZT-I35: a step whose own rows do not name the items (Generate report's rows are its reports) but that made a file for
     * an item did that item's work: the item's entry is done with its file, not "left out", and the item goes on to the next
     * step that makes it a file. An item it made nothing for is still left out there.
     */
    @Test
    void aStepThatMadeAnItemAFileDidItsWorkThoughItsRowsDoNotNameIt() throws Exception {
        String listed = this.write("list", Arrays.asList(row("key", "in/a.json"), row("key", "in/b.json")));
        // As live run #2950's: the step's rows carry the key column, but with its reports' own values, which name no item.
        String reports = this.write("reports", Collections.singletonList(row("key", "RPT-0001", "version", 1)));
        List<ItemTracker.StepInfo> steps = Arrays.asList(step(0, "read", ItemRole.SOURCE, "key"), step(1, "report", ItemRole.EACH, null),
            step(2, "json", ItemRole.EACH, null));
        ItemTracker tracker = ItemTracker.open(RUN, 1, this.store, this.engine, steps, -1, 0, null);
        tracker.beforeStep(0, null, Collections.emptyList());
        tracker.afterStep(this.done(0, null, listed, 1));
        ItemTracker.Outcome report = this.done(1, listed, reports, 2);
        report.reports = Collections.singletonList(ItemReport.file(row("key", "in/a.json"), "Intake_a_v1.pdf", null, null, 4096));
        tracker.afterStep(report);
        ItemTracker.Outcome json = this.done(2, reports, this.write("saved", Collections.singletonList(row("file", "intake-a.json"))), 3);
        json.reports = Collections.singletonList(ItemReport.file(row("key", "in/a.json"), "intake-a.json", null, null, 512));
        tracker.afterStep(json);
        tracker.finish(true, false, false, null);

        ItemRow a = this.store.currentOf(RUN, "in/a.json");
        assertThat(a.status).isEqualTo("done");
        assertThat(a.step(1).status).as("the report step made a's report").isEqualTo("done");
        assertThat(a.step(1).files).isEqualTo(1);
        assertThat(a.step(1).note).isEqualTo("1 file");
        assertThat(a.step(2).status).as("and the JSON step its JSON").isEqualTo("done");
        assertThat(a.step(2).files).isEqualTo(1);
        assertThat(a.fileCount).isEqualTo(2);
        ItemRow b = this.store.currentOf(RUN, "in/b.json");
        assertThat(b.step(1).status).as("nothing made for b: it is left out there").isEqualTo("left_out");
        assertThat(b.step(2)).isNull();
    }

    @Test
    void anItemsCallKeyIsTheSameWhateverItsPosition() throws Exception {
        String listed = this.write("list", Arrays.asList(row("key", "in/a.m4a"), row("key", "in/b.m4a")));
        List<ItemTracker.StepInfo> steps = Arrays.asList(step(0, "read", ItemRole.SOURCE, "key"), step(1, "ask", ItemRole.EACH, null));
        ItemTracker tracker = ItemTracker.open(RUN, 1, this.store, this.engine, steps, -1, 0, null);
        tracker.beforeStep(0, null, Collections.emptyList());
        tracker.afterStep(this.done(0, null, listed, 1));
        String b = tracker.tagOf(row("key", "in/b.m4a"), 1);
        assertThat(tracker.tagOf(row("key", "in/b.m4a"), 0)).isEqualTo(b);
        assertThat(tracker.tagOf(row("key", "in/a.m4a"), 1)).isNotEqualTo(b);
        assertThat(tracker.tagOf(row("other", "x"), 4)).as("a row of no item keeps its number").isEqualTo("5");
    }

    @Test
    void aPipelineWithoutAPerItemStepIsNotAnItemPipeline() {
        assertThat(ItemTracker.open(RUN, 1, this.store, this.engine, Collections.<ItemTracker.StepInfo>emptyList(), -1, 0, null)).isNull();
        assertThat(ItemRole.SOURCE.makesItemPipeline()).isFalse();
        assertThat(ItemRole.SHARED.makesItemPipeline()).isFalse();
        assertThat(ItemRole.EACH.makesItemPipeline()).isTrue();
        assertThat(ItemRole.FAN_OUT.makesItemPipeline()).isTrue();
    }
}
