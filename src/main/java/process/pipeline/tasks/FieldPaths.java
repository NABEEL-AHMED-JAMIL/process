package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import process.pipeline.data.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Columns read out of a nested JSON element by dot paths ({@code fields: [{path, target}]}, read_api and enrich). A path
 * may fan out over one list with {@code []}: {@code results[].term} and {@code results[].count} read the list element by
 * element, so the element becomes one row per list item with the other columns repeated (one row with those columns
 * empty when the list is empty or absent). Every fanning path must fan out over the same list, once.
 */
final class FieldPaths {

    private FieldPaths() {
    }

    /** The list every fanning path shares ("results[]"), or null when none fans out; an exception for two lists. */
    static String fanOut(List<Map<String, Object>> fields) {
        String list = null;
        for (Map<String, Object> field : fields) {
            String path = Configs.text(field, "path", "");
            int at = at(path);
            if (at < 0) {
                continue;
            }
            String prefix = path.substring(0, at + width(path, at));
            if (at(rest(path)) >= 0) {
                throw new IllegalArgumentException("A path may fan out once: " + path + ".");
            }
            if (list != null && !list.equals(prefix)) {
                throw new IllegalArgumentException("Paths that fan out must fan out over the same list: " + list + " and " + prefix + ".");
            }
            list = prefix;
        }
        return list;
    }

    /** The columns of one element: one map per item of the list the fanning paths share, or one map. */
    static List<Map<String, JsonNode>> rows(JsonNode element, List<Map<String, Object>> fields) {
        String list = fanOut(fields);
        JsonNode items = list == null ? null : Values.all(element, list);
        int copies = items == null || !items.isArray() || items.size() == 0 ? 1 : items.size();
        List<Map<String, JsonNode>> out = new ArrayList<>(copies);
        for (int i = 0; i < copies; i++) {
            Map<String, JsonNode> row = new LinkedHashMap<>();
            for (Map<String, Object> field : fields) {
                String path = Configs.text(field, "path", "");
                JsonNode value;
                if (list != null && at(path) >= 0) {
                    JsonNode item = items != null && items.isArray() && items.size() > i ? items.get(i) : null;
                    String rest = rest(path);
                    value = item == null ? null : rest.isEmpty() ? item : Values.at(item, rest);
                } else {
                    value = Values.at(element, path);
                }
                row.put(Configs.text(field, "target", ""), value == null || value.isMissingNode() ? null : value);
            }
            out.add(row);
        }
        return out;
    }

    private static int at(String path) {
        int a = path.indexOf("[]");
        int b = path.indexOf("[*]");
        return a < 0 ? b : b < 0 ? a : Math.min(a, b);
    }

    private static int width(String path, int at) {
        return path.startsWith("[*]", at) ? 3 : 2;
    }

    private static String rest(String path) {
        int at = at(path);
        String rest = path.substring(at + width(path, at));
        return rest.startsWith(".") ? rest.substring(1) : rest;
    }
}
