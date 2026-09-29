package process.pipeline.tasks;

import process.pipeline.DefinitionProblem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The checks every task's config shares. */
final class Configs {

    private Configs() {
    }

    /** One problem per key the task does not know. */
    static void onlyKnownKeys(Map<String, Object> config, List<DefinitionProblem> problems, String... known) {
        Set<String> allowed = new HashSet<>(Arrays.asList(known));
        for (String key : config.keySet()) {
            if (!allowed.contains(key)) {
                problems.add(new DefinitionProblem(key, String.format("unknown setting '%s'", key)));
            }
        }
    }

    /** A text setting, trimmed; the default when absent or blank. */
    static String text(Map<String, Object> config, String key, String otherwise) {
        Object value = config.get(key);
        return value == null || value.toString().trim().isEmpty() ? otherwise : value.toString().trim();
    }

    /** A whole-number setting; the default when absent. */
    static Integer integer(Map<String, Object> config, String key, Integer otherwise) {
        Object value = config.get(key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : otherwise;
    }

    static Long longValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    static boolean bool(Map<String, Object> config, String key, boolean otherwise) {
        Object value = config.get(key);
        return value instanceof Boolean ? (Boolean) value : otherwise;
    }

    /** A list of objects setting (a repeatable group); empty when absent. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> config, String key) {
        Object value = config.get(key);
        List<Map<String, Object>> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof Map) {
                    list.add((Map<String, Object>) item);
                }
            }
        }
        return list;
    }

    /** A list of text setting; empty when absent. */
    static List<String> texts(Map<String, Object> config, String key) {
        Object value = config.get(key);
        List<String> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    list.add(item.toString().trim());
                }
            }
        }
        return list;
    }

    /** An object-of-text setting (a request's variables); empty when absent. */
    static Map<String, String> textMap(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((name, text) -> map.put(String.valueOf(name), text == null ? null : text.toString()));
        return map;
    }

    /** A value a row may hold: text, a number, true/false, or nothing. */
    static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
