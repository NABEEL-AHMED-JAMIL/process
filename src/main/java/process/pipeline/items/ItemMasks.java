package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.barco.platform.sensitive.SensitiveValues;
import org.barco.platform.sensitive.SensitiveValues.Kind;
import process.pipeline.data.RowSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an item's data must not show this person (MIG-420): the workspace's data policy masking, as Ask your data applies it
 * to a pipeline result (ai-service AskMasks). The Data Catalog's sensitive assets the person has no access to
 * (GET analyticsCatalog.json/masks, their own token): every column one of them tags is masked by name -- in the rows, and
 * inside a JSON answer's fields of that name -- and every pattern kind they hold is masked wherever it shows. A pipeline whose
 * settings say its data is sensitive has its rows' columns read too (SensitiveValues.columnKind: a person's name, an email,
 * a phone number... by the column's name and values), so masking holds from its first run, before any file is scanned --
 * and, ZT-M21, from its first ROW: a sensitive pipeline's columns are read by {@link #sensitiveColumnKind}, where a small
 * sample (one item's fields, one patient's result, a short batch) never weakens masking.
 * When the catalog cannot be asked, every pattern kind is masked everywhere (fail closed). A workspace administrator (and a
 * managed session, which acts as one) has none.
 */
public final class ItemMasks {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final ItemMasks NONE = new ItemMasks(new LinkedHashMap<>(), EnumSet.noneOf(Kind.class), false, false);

    private final Map<String, Kind> columns;
    private final Set<Kind> kinds;
    private final boolean failClosed;
    /** A sensitive pipeline: each step's rows are read for sensitive columns too. */
    private final boolean detect;

    ItemMasks(Map<String, Kind> columns, Set<Kind> kinds, boolean failClosed, boolean detect) {
        this.columns = columns;
        this.kinds = kinds;
        this.failClosed = failClosed;
        this.detect = detect;
    }

    /** The masks from the catalog's answer (its data array); fail closed when there is none. */
    static ItemMasks of(JsonNode data, boolean readable, boolean sensitivePipeline) {
        if (!readable) {
            return new ItemMasks(new LinkedHashMap<>(), EnumSet.copyOf(SensitiveValues.patternKinds()), true, sensitivePipeline);
        }
        Map<String, Kind> columns = new LinkedHashMap<>();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (data != null) {
            for (JsonNode rule : data) {
                rule.path("columns").properties().iterator().forEachRemaining(e -> Kind.ofTag(e.getValue().asText())
                    .ifPresent(kind -> columns.put(e.getKey().toLowerCase(Locale.ROOT), kind)));
                for (JsonNode k : rule.path("kinds")) {
                    Kind.ofTag(k.asText()).ifPresent(kinds::add);
                }
            }
        }
        return new ItemMasks(columns, kinds, false, sensitivePipeline);
    }

    /**
     * MIG-424: the masks for data that leaves the run for a reader who is no one person -- a workflow request's history, the
     * customer API's run result: every sensitive asset of the workspace (analytics' service read, {@link WorkspaceMasks}), as
     * for a member with no access to any of them; fail closed when it could not be read (null).
     */
    public static ItemMasks forWorkspace(JsonNode sensitiveAssets, boolean sensitivePipeline) {
        return of(sensitiveAssets, sensitiveAssets != null, sensitivePipeline);
    }

    /** No masks: a workspace administrator's read (or a managed session's) of a pipeline that is not marked sensitive. */
    static ItemMasks none() {
        return NONE;
    }

    /** For a sensitive pipeline read by someone who is not an administrator: its rows' sensitive columns, read from them. */
    ItemMasks detecting() {
        return new ItemMasks(this.columns, this.kinds, this.failClosed, true);
    }

    public boolean any() {
        return this.failClosed || this.detect || !this.columns.isEmpty() || !this.kinds.isEmpty();
    }

    public boolean failedClosed() {
        return this.failClosed;
    }

    /**
     * These rows as the person may see them; the columns masked are added to {@code masked}. A sensitive pipeline's rows are
     * read first for the columns that hold a person's details.
     */
    public List<Map<String, Object>> rows(List<Map<String, Object>> rows, Collection<String> columnNames, Set<String> masked) {
        return this.rows(rows, columnNames, masked, new LinkedHashMap<>());
    }

    /**
     * The same, for one batch of many (a dataset streamed to a download): a column found to hold a person's details in one
     * batch stays masked in every later one ({@code found} carries them), so a short last batch never shows what the
     * first hid.
     */
    List<Map<String, Object>> rows(List<Map<String, Object>> rows, Collection<String> columnNames, Set<String> masked, Map<String, Kind> found) {
        if (!this.any() || rows.isEmpty()) {
            return rows;
        }
        Map<String, Kind> byName = new LinkedHashMap<>(this.columns);
        found.forEach(byName::putIfAbsent);
        if (this.detect) {
            for (String column : columnNames) {
                if (byName.containsKey(column.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> sample = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object v = row.get(column);
                    if (v != null && !(v instanceof Boolean) && !(v instanceof Number && column.toLowerCase(Locale.ROOT).endsWith("_count"))) {
                        sample.add(String.valueOf(v));
                    }
                }
                sensitiveColumnKind(column, sample).ifPresent(kind -> {
                    byName.put(column.toLowerCase(Locale.ROOT), kind);
                    found.put(column.toLowerCase(Locale.ROOT), kind);
                });
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> shown = new LinkedHashMap<>();
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                shown.put(cell.getKey(), this.value(byName, cell.getKey(), cell.getValue(), masked));
            }
            out.add(shown);
        }
        return out;
    }

    /** One value of a column, masked by its name, its pattern kinds, or (a JSON text) inside it. */
    Object value(Map<String, Kind> byName, String column, Object value, Set<String> masked) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        Kind kind = column == null ? null : byName.get(column.toLowerCase(Locale.ROOT));
        String text = String.valueOf(value);
        if (kind != null) {
            masked.add(column);
            return SensitiveValues.mask(text, kind);
        }
        if (value instanceof Number && !this.failClosed) {
            return value;
        }
        String trimmed = text.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonNode node = JSON.readTree(trimmed);
                boolean[] changed = {false};
                JsonNode shown = this.node(byName, node, changed);
                if (changed[0]) {
                    masked.add(column);
                    return JSON.writeValueAsString(shown);
                }
                return value;
            } catch (Exception notJson) {
                // plain text
            }
        }
        Set<Kind> all = EnumSet.noneOf(Kind.class);
        all.addAll(this.kinds);
        if (this.failClosed) {
            all.addAll(SensitiveValues.patternKinds());
        }
        if (all.isEmpty()) {
            return value;
        }
        String out = SensitiveValues.maskText(text, all);
        if (!out.equals(text)) {
            masked.add(column);
            return out;
        }
        return value;
    }

    private JsonNode node(Map<String, Kind> byName, JsonNode node, boolean[] changed) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Kind kind = byName.get(field.getKey().toLowerCase(Locale.ROOT));
                if (kind != null && field.getValue().isValueNode() && !field.getValue().isNull()) {
                    copy.set(field.getKey(), TextNode.valueOf(SensitiveValues.mask(field.getValue().asText(), kind)));
                    changed[0] = true;
                } else {
                    copy.set(field.getKey(), this.node(byName, field.getValue(), changed));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            for (JsonNode element : node) {
                copy.add(this.node(byName, element, changed));
            }
            return copy;
        }
        if (node.isTextual()) {
            Set<Kind> all = EnumSet.noneOf(Kind.class);
            all.addAll(this.kinds);
            if (this.failClosed) {
                all.addAll(SensitiveValues.patternKinds());
            }
            if (!all.isEmpty()) {
                String out = SensitiveValues.maskText(node.asText(), all);
                if (!out.equals(node.asText())) {
                    changed[0] = true;
                    return TextNode.valueOf(out);
                }
            }
        }
        return node;
    }

    /**
     * ZT-M21: what a column of SENSITIVE data holds -- a pipeline marked sensitive, a dataset the catalog holds as
     * Confidential or Restricted. {@link SensitiveValues#columnKind} decides a column from at least
     * {@link SensitiveValues#MIN_VALUES} values, so one patient's row (a workflow's result, an item's fields, a one-row
     * output) said nothing and showed. Here a small sample never weakens masking: under that many values, the column is a
     * kind when ANY of its values is one (each read as a column of that value; a bare "name" as a person's name column,
     * since two values cannot show the distinctness a list of people has). At that many values or more the platform's rule
     * holds unchanged. Data that is not sensitive is never read with this rule.
     */
    /**
     * ZT-M21: a column's name as the platform's column rules read it. SensitiveValues' name patterns take "_", "-" and a
     * space as one separator; a flattened JSON path's "." is one too -- "patient.name" names a patient's name as
     * "patient_name" does, and "contact.address" an address -- so it is read as "_".
     */
    public static String columnName(String column) {
        return column == null ? "" : column.trim().replace('.', '_');
    }

    public static Optional<Kind> sensitiveColumnKind(String column, Collection<String> sample) {
        Optional<Kind> kind = SensitiveValues.columnKind(columnName(column), sample);
        if (kind.isPresent()) {
            return kind;
        }
        List<String> values = new ArrayList<>();
        for (String s : sample == null ? Collections.<String>emptyList() : sample) {
            if (s != null && !s.trim().isEmpty()) {
                values.add(s.trim());
            }
        }
        if (values.isEmpty() || values.size() >= SensitiveValues.MIN_VALUES) {
            return Optional.empty();
        }
        String name = columnName(column);
        String asked = "name".equalsIgnoreCase(name) ? "full_name" : name;
        for (String v : values) {
            kind = SensitiveValues.columnKind(asked, Collections.nCopies(SensitiveValues.MIN_VALUES, v));
            if (kind.isPresent()) {
                return kind;
            }
        }
        return Optional.empty();
    }

    /**
     * ZT-M21: a dataset as this person may see it, batch by batch (a run's output downloaded or viewed): each batch masked as
     * {@link #rows} masks, a column found in one batch masked in the rest. No masks: the source itself.
     */
    public RowSource rows(RowSource source) {
        if (!this.any()) {
            return source;
        }
        Map<String, Kind> found = new LinkedHashMap<>();
        ItemMasks masks = this;
        return new RowSource() {
            @Override
            public List<String> columns() {
                return source.columns();
            }

            @Override
            public long size() {
                return source.size();
            }

            @Override
            public List<Map<String, Object>> next() throws Exception {
                List<Map<String, Object>> batch = source.next();
                if (batch == null) {
                    return null;
                }
                List<String> names = source.columns() == null || source.columns().isEmpty()
                    ? new ArrayList<>(batch.get(0).keySet()) : source.columns();
                return masks.rows(batch, names, new HashSet<>(), found);
            }

            @Override
            public void close() throws Exception {
                source.close();
            }
        };
    }

    /** The column names this person has masked by name (for the console's "masked" mark on a field it lists). */
    public Set<String> maskedNames(Collection<String> columnNames) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : columnNames) {
            if (this.columns.containsKey(c.toLowerCase(Locale.ROOT))) {
                out.add(c);
            }
        }
        return out;
    }
}
