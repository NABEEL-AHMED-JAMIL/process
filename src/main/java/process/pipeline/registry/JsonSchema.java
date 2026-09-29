package process.pipeline.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A step task's config schema, written in a line (MIG-231): the small subset of JSON Schema the console builds a step's
 * side panel form from (MIG-249) and {@link ConfigSchemaValidator} checks a step's config against. The subset:
 *
 * <ul>
 *   <li>{@code type}: string, integer, number, boolean, object, array -- or a list of them (a free-form field, shown
 *       as a JSON editor), {@code null} allowed in such a list;</li>
 *   <li>{@code title}, {@code description}, {@code default};</li>
 *   <li>{@code enum} (a closed list, shown as a select);</li>
 *   <li>strings: {@code minLength}, {@code maxLength}, {@code pattern}; numbers: {@code minimum}, {@code maximum};</li>
 *   <li>objects: {@code properties} (in the order the form shows them), {@code required}, {@code additionalProperties}
 *       (false -- the default here -- or a schema every other value holds to: a map such as a request's variables);</li>
 *   <li>arrays: {@code items} (one schema; an array of objects is a repeatable group of fields), {@code minItems},
 *       {@code maxItems};</li>
 *   <li>{@code format}, a hint for the form's widget, never a rule except {@code step}: {@code column} (a column of the
 *       step's input), {@code step} (the key of an earlier step -- checked), {@code sql}, {@code template} (text with
 *       {{placeholders}}), {@code multiline}, {@code api-request}, {@code api-environment}, {@code data-contract},
 *       {@code db-connection}, {@code bucket} (a pick-list from another service's catalogue).</li>
 * </ul>
 */
public final class JsonSchema {

    private final Map<String, Object> node = new LinkedHashMap<>();

    private JsonSchema(Object type) {
        this.node.put("type", type);
    }

    public static JsonSchema object() {
        JsonSchema schema = new JsonSchema("object");
        schema.node.put("properties", new LinkedHashMap<String, Object>());
        schema.node.put("additionalProperties", false);
        return schema;
    }

    /** An object whose keys are free and whose every value holds to {@code values} (e.g. a request's variables). */
    public static JsonSchema map(JsonSchema values) {
        JsonSchema schema = new JsonSchema("object");
        schema.node.put("additionalProperties", values.toMap());
        return schema;
    }

    public static JsonSchema string() {
        return new JsonSchema("string");
    }

    public static JsonSchema integer() {
        return new JsonSchema("integer");
    }

    public static JsonSchema number() {
        return new JsonSchema("number");
    }

    public static JsonSchema bool() {
        return new JsonSchema("boolean");
    }

    public static JsonSchema array(JsonSchema items) {
        JsonSchema schema = new JsonSchema("array");
        schema.node.put("items", items.toMap());
        return schema;
    }

    /** A value of any of these types: e.g. a filter's compared value, text, a number, true/false or null. */
    public static JsonSchema anyOf(String... types) {
        return new JsonSchema(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(types))));
    }

    /** A plain value a row may hold: text, a number, true/false or null. */
    public static JsonSchema scalar() {
        return anyOf("string", "number", "boolean", "null");
    }

    public JsonSchema title(String title) {
        this.node.put("title", title);
        return this;
    }

    public JsonSchema description(String description) {
        this.node.put("description", description);
        return this;
    }

    public JsonSchema defaultValue(Object value) {
        this.node.put("default", value);
        return this;
    }

    public JsonSchema enumOf(String... values) {
        this.node.put("enum", Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values))));
        return this;
    }

    public JsonSchema minimum(long minimum) {
        this.node.put("minimum", minimum);
        return this;
    }

    public JsonSchema maximum(long maximum) {
        this.node.put("maximum", maximum);
        return this;
    }

    public JsonSchema minLength(int minLength) {
        this.node.put("minLength", minLength);
        return this;
    }

    public JsonSchema maxLength(int maxLength) {
        this.node.put("maxLength", maxLength);
        return this;
    }

    public JsonSchema pattern(String pattern) {
        this.node.put("pattern", pattern);
        return this;
    }

    public JsonSchema minItems(int minItems) {
        this.node.put("minItems", minItems);
        return this;
    }

    public JsonSchema maxItems(int maxItems) {
        this.node.put("maxItems", maxItems);
        return this;
    }

    public JsonSchema format(String format) {
        this.node.put("format", format);
        return this;
    }

    /** A property of an object, in the order the form shows it. */
    @SuppressWarnings("unchecked")
    public JsonSchema property(String name, JsonSchema schema) {
        Object properties = this.node.get("properties");
        if (!(properties instanceof Map)) {
            throw new IllegalStateException("Only an object has properties.");
        }
        ((Map<String, Object>) properties).put(name, schema.toMap());
        return this;
    }

    /** A property the config must set. */
    public JsonSchema required(String name, JsonSchema schema) {
        this.property(name, schema);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) this.node.computeIfAbsent("required", absent -> new ArrayList<String>());
        required.add(name);
        return this;
    }

    public Map<String, Object> toMap() {
        return this.node;
    }
}
