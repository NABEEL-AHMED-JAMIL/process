package process.pipeline.registry;

import process.pipeline.DefinitionProblem;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Checks a step's config against its task's config schema (MIG-231): every problem, each at its path relative to the
 * config ({@code "mappings[1].target"}, {@code "$"} for the config itself), in the words a person reads beside the
 * field. Only the subset {@link JsonSchema} writes is understood; a keyword outside it is ignored, never guessed at.
 *
 * The one check beyond the schema: a string of {@code format: step} names an earlier step of the same definition (the
 * right side of a join).
 */
public final class ConfigSchemaValidator {

    private ConfigSchemaValidator() {
    }

    /** Every problem of {@code config} under {@code schema}; {@code earlierSteps} are the keys a {@code step} may name. */
    public static List<DefinitionProblem> problems(Map<String, Object> schema, Object config, Set<String> earlierSteps) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (schema != null) {
            check(schema, config, "", earlierSteps == null ? Collections.<String>emptySet() : earlierSteps, problems);
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String at, Set<String> earlier, List<DefinitionProblem> problems) {
        List<String> types = typesOf(schema.get("type"));
        if (!types.isEmpty() && types.stream().noneMatch(type -> is(type, value))) {
            problems.add(new DefinitionProblem(at, "must be " + words(types)));
            return;
        }
        Object options = schema.get("enum");
        if (options instanceof Collection && value != null && !((Collection<Object>) options).contains(value)) {
            problems.add(new DefinitionProblem(at, "one of " + options));
            return;
        }
        if (value instanceof String) {
            text((String) value, schema, at, earlier, problems);
        } else if (value instanceof Number) {
            number((Number) value, schema, at, problems);
        } else if (value instanceof Map) {
            object((Map<String, Object>) value, schema, at, earlier, problems);
        } else if (value instanceof List) {
            list((List<Object>) value, schema, at, earlier, problems);
        }
    }

    private static void text(String value, Map<String, Object> schema, String at, Set<String> earlier, List<DefinitionProblem> problems) {
        Integer min = integer(schema.get("minLength"));
        Integer max = integer(schema.get("maxLength"));
        if (min != null && value.trim().length() < min) {
            problems.add(new DefinitionProblem(at, min == 1 ? "must not be empty" : String.format("at least %d characters", min)));
        } else if (max != null && value.length() > max) {
            problems.add(new DefinitionProblem(at, String.format("at most %d characters", max)));
        } else if (schema.get("pattern") instanceof String && !matches((String) schema.get("pattern"), value)) {
            Object description = schema.get("description");
            problems.add(new DefinitionProblem(at, "is not in the expected form" + (description == null ? "" : ": " + description)));
        } else if ("step".equals(schema.get("format")) && !earlier.contains(value)) {
            problems.add(new DefinitionProblem(at, String.format("'%s' is not an earlier step", value)));
        }
    }

    private static void number(Number value, Map<String, Object> schema, String at, List<DefinitionProblem> problems) {
        BigDecimal number = decimal(value);
        Object min = schema.get("minimum");
        Object max = schema.get("maximum");
        if (min instanceof Number && number.compareTo(decimal((Number) min)) < 0) {
            problems.add(new DefinitionProblem(at, "at least " + min));
        } else if (max instanceof Number && number.compareTo(decimal((Number) max)) > 0) {
            problems.add(new DefinitionProblem(at, "at most " + max));
        }
    }

    @SuppressWarnings("unchecked")
    private static void object(Map<String, Object> value, Map<String, Object> schema, String at, Set<String> earlier,
        List<DefinitionProblem> problems) {
        Map<String, Object> properties = schema.get("properties") instanceof Map ? (Map<String, Object>) schema.get("properties")
            : Collections.<String, Object>emptyMap();
        Object required = schema.get("required");
        if (required instanceof Collection) {
            for (Object name : (Collection<Object>) required) {
                if (value.get(String.valueOf(name)) == null) {
                    problems.add(new DefinitionProblem(join(at, String.valueOf(name)), "required"));
                }
            }
        }
        Object additional = schema.containsKey("additionalProperties") ? schema.get("additionalProperties") : Boolean.TRUE;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String path = join(at, entry.getKey());
            Object property = properties.get(entry.getKey());
            if (property instanceof Map) {
                if (entry.getValue() != null) {
                    check((Map<String, Object>) property, entry.getValue(), path, earlier, problems);
                }
            } else if (Boolean.FALSE.equals(additional)) {
                problems.add(new DefinitionProblem(path, String.format("unknown setting '%s'", entry.getKey())));
            } else if (additional instanceof Map) {
                check((Map<String, Object>) additional, entry.getValue(), path, earlier, problems);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void list(List<Object> value, Map<String, Object> schema, String at, Set<String> earlier, List<DefinitionProblem> problems) {
        Integer min = integer(schema.get("minItems"));
        Integer max = integer(schema.get("maxItems"));
        if (min != null && value.size() < min) {
            problems.add(new DefinitionProblem(at, min == 1 ? "at least one item" : String.format("at least %d items", min)));
        }
        if (max != null && value.size() > max) {
            problems.add(new DefinitionProblem(at, String.format("at most %d items; found %d", max, value.size())));
            return;
        }
        if (schema.get("items") instanceof Map) {
            for (int i = 0; i < value.size(); i++) {
                check((Map<String, Object>) schema.get("items"), value.get(i), at + "[" + i + "]", earlier, problems);
            }
        }
    }

    private static List<String> typesOf(Object type) {
        if (type instanceof String) {
            return Collections.singletonList((String) type);
        }
        if (type instanceof Collection) {
            return ((Collection<?>) type).stream().map(String::valueOf).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    static boolean is(String type, Object value) {
        switch (type) {
            case "string":
                return value instanceof String;
            case "integer":
                return isInteger(value);
            case "number":
                return value instanceof Number;
            case "boolean":
                return value instanceof Boolean;
            case "object":
                return value instanceof Map;
            case "array":
                return value instanceof List;
            case "null":
                return value == null;
            default:
                return true;
        }
    }

    private static boolean isInteger(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
            || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal number = decimal((Number) value);
            return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
        }
        return false;
    }

    /** "text", "text or a number", "text, a number, true/false or null". */
    private static String words(List<String> types) {
        List<String> words = new ArrayList<>();
        for (String type : types) {
            words.add(types.size() > 1 && "boolean".equals(type) ? "true/false" : word(type));
        }
        if (words.size() == 1) {
            return words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " or " + words.get(words.size() - 1);
    }

    private static String word(String type) {
        switch (type) {
            case "string":
                return "text";
            case "integer":
                return "a whole number";
            case "number":
                return "a number";
            case "boolean":
                return "true or false";
            case "object":
                return "an object";
            case "array":
                return "a list";
            case "null":
                return "null";
            default:
                return type;
        }
    }

    private static BigDecimal decimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.valueOf(number.longValue());
    }

    private static Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static boolean matches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException broken) {
            return true;
        }
    }

    private static String join(String at, String name) {
        return at.isEmpty() ? name : at + "." + name;
    }
}
