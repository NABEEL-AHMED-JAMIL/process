package process.pipeline.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a row's values are and how they compare (MIG-231). A row holds plain values: text, a number, true/false or
 * null. Anything nested that comes in (an API's JSON, a JSON file) is kept as its JSON text in one column, never as a
 * structure a later step would have to guess at.
 */
public final class Values {

    public static final ObjectMapper JSON = new ObjectMapper();

    private Values() {
    }

    /** A JSON value as a row value: scalars as themselves, an object or array as its JSON text. */
    public static Object scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.asLong() : node.decimalValue();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException unwritable) {
            return node.toString();
        }
    }

    /** A JSON value as a row: an object's fields as columns (nested values as JSON text); anything else as {value}. */
    public static Map<String, Object> row(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                row.put(field.getKey(), scalar(field.getValue()));
            }
        } else {
            row.put("value", scalar(node));
        }
        return row;
    }

    /**
     * The value at a dot path ({@code data.items}, {@code results[0].id}); the node itself for an empty path, a missing
     * node when any part is absent.
     */
    public static JsonNode at(JsonNode node, String path) {
        if (path == null || path.trim().isEmpty() || node == null) {
            return node;
        }
        JsonNode at = node;
        for (String part : path.trim().split("\\.")) {
            String name = part;
            int bracket = part.indexOf('[');
            if (bracket >= 0) {
                name = part.substring(0, bracket);
            }
            if (!name.isEmpty()) {
                at = at.path(name);
            }
            while (bracket >= 0) {
                int close = part.indexOf(']', bracket);
                if (close < 0) {
                    return MissingNode.getInstance();
                }
                try {
                    at = at.path(Integer.parseInt(part.substring(bracket + 1, close).trim()));
                } catch (NumberFormatException notIndex) {
                    return MissingNode.getInstance();
                }
                bracket = part.indexOf('[', close);
            }
        }
        return at;
    }

    /** Text, for concatenation and comparison: null stays null, numbers in their plain form. */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal number = new BigDecimal(value.toString());
            return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    /** The number a value is, or holds as text; null when it is neither. */
    public static BigDecimal number(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** A number as a row value: a whole number as a long when it fits, else a double. */
    public static Object plain(BigDecimal number) {
        if (number == null) {
            return null;
        }
        BigDecimal stripped = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
            return stripped.longValueExact();
        }
        return number.doubleValue();
    }

    /**
     * A value as a key two datasets can meet on: 1, 1.0 and "1" are one key (a CSV's text meets an API's number), but
     * "001" is not 1 -- text is a number only in its plain form. Null is no key.
     */
    public static String key(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal number = number(value);
        if (number != null && value instanceof String && !text(number).equals(((String) value).trim())) {
            number = null;
        }
        return number != null ? "n:" + text(number) : "s:" + value;
    }

    /** Numbers by value when both are numbers (or numeric text), else text; null first. */
    public static int compare(Object left, Object right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        BigDecimal a = number(left);
        BigDecimal b = number(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return text(left).compareTo(text(right));
    }

    /** Whether two values are equal as {@link #compare} sees them. */
    public static boolean same(Object left, Object right) {
        return compare(left, right) == 0;
    }
}
