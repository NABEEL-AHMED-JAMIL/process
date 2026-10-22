package process.pipeline.items;

import process.pipeline.data.RowSource;
import process.pipeline.data.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * MIG-421: an item's own values, kept on its record (run_item.fields) when its attempt ends, so a schedule's Items tab finds
 * an item by a value ("risk = High") across its runs from the item records alone -- never by opening every run's outputs.
 *
 * <ul>
 *   <li>Read once, by pointer: the item's last step with rows of its own (not a shared output), at its first row there.</li>
 *   <li>Scalars only: text, numbers, true/false; a JSON text, a list or an object is left out (the item panel shows them);
 *       at most {@value #MAX_FIELDS} values, each cut at {@value #MAX_TEXT} characters.</li>
 *   <li>Kept with the dataset they came from (fields_ds): the dataset sweep clears them when it removes it.</li>
 *   <li>Never shown as kept: every read masks them for the person ({@link ItemMasks}), and a search matches only what the
 *       person would see.</li>
 * </ul>
 */
public final class ItemFields {

    /** The most values an item keeps. */
    public static final int MAX_FIELDS = 60;
    /** The longest text a value keeps. */
    public static final int MAX_TEXT = 200;

    /** How the values are read: a run dataset's storage key, and its rows. */
    public interface Opener {
        /** The storage key of a run dataset, or null when it is gone. */
        String storageKeyOf(long runDatasetId);

        /** A dataset by its storage key, a batch at a time. */
        RowSource open(String storageKey) throws Exception;
    }

    private ItemFields() {
    }

    /** The item's last step that has its own rows: where its values are read. */
    static ItemRow.StepEntry lastData(ItemRow row) {
        ItemRow.StepEntry last = null;
        for (ItemRow.StepEntry entry : row.steps) {
            if (entry.ds != null && entry.rows != null && !entry.rows.isEmpty() && !ItemRow.StepEntry.SHARED.equals(entry.status)) {
                last = entry;
            }
        }
        return last;
    }

    /**
     * Each row's values (row.fields, row.fieldsDs), one pass over each dataset they are in; a row whose data is gone keeps
     * none. Returns the rows whose values were read.
     */
    public static List<ItemRow> read(Collection<ItemRow> rows, Opener opener) {
        Map<Long, List<ItemRow>> byDataset = new LinkedHashMap<>();
        for (ItemRow row : rows) {
            ItemRow.StepEntry last = lastData(row);
            if (last != null) {
                byDataset.computeIfAbsent(last.ds, k -> new ArrayList<>()).add(row);
            }
        }
        List<ItemRow> read = new ArrayList<>();
        for (Map.Entry<Long, List<ItemRow>> e : byDataset.entrySet()) {
            String key = opener.storageKeyOf(e.getKey());
            if (key == null) {
                continue;
            }
            Map<Integer, List<ItemRow>> at = new HashMap<>();
            TreeSet<Integer> wanted = new TreeSet<>();
            for (ItemRow row : e.getValue()) {
                int p = lastData(row).rows.get(0);
                at.computeIfAbsent(p, k -> new ArrayList<>()).add(row);
                wanted.add(p);
            }
            int p = 0;
            try (RowSource source = opener.open(key)) {
                for (List<Map<String, Object>> batch = source.next(); batch != null && p <= wanted.last(); batch = source.next()) {
                    for (Map<String, Object> values : batch) {
                        List<ItemRow> owners = at.get(p);
                        if (owners != null) {
                            Map<String, Object> kept = scalars(values);
                            for (ItemRow row : owners) {
                                row.fields = kept;
                                row.fieldsDs = e.getKey();
                                read.add(row);
                            }
                        }
                        p++;
                    }
                }
            } catch (Exception unread) {
                // The dataset could not be read back: these items keep no values (the panel still reads them by pointer).
            }
        }
        return read;
    }

    /** A row's scalar values, in column order, at most {@value #MAX_FIELDS}, texts cut at {@value #MAX_TEXT}. */
    public static Map<String, Object> scalars(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            return out;
        }
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            if (out.size() >= MAX_FIELDS) {
                break;
            }
            Object v = cell.getValue();
            if (v == null || cell.getKey() == null || cell.getKey().length() > 128) {
                continue;
            }
            if (v instanceof Boolean || v instanceof Number) {
                out.put(cell.getKey(), v);
                continue;
            }
            if (v instanceof Map || v instanceof Collection) {
                continue;
            }
            String text = Values.text(v);
            text = text == null ? String.valueOf(v) : text;
            String trimmed = text.trim();
            if (trimmed.isEmpty() || (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                continue;
            }
            out.put(cell.getKey(), trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT - 1) + "…" : trimmed);
        }
        return out;
    }

    /**
     * Whether a field's name answers the name a search asked for: the same, ignoring case, or its last part ("risk" names
     * "status.risk").
     */
    public static boolean names(String field, String asked) {
        if (field == null || asked == null) {
            return false;
        }
        String f = field.toLowerCase(Locale.ROOT);
        String a = asked.toLowerCase(Locale.ROOT).trim();
        return f.equals(a) || f.endsWith("." + a);
    }

    /** The values with nothing in them, for a row that keeps none. */
    public static Map<String, Object> none() {
        return Collections.emptyMap();
    }
}
