package process.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pipeline definition as JSON -- what is stored -- and as YAML, a view of the same definition (MIG-230). Both read
 * into one {@link PipelineDefinition} and are written from it, so editing either produces the same saved definition
 * (MIG-249's acceptance): YAML -> definition -> JSON -> definition -> YAML is the identity on the definition.
 *
 * Reading is strict about names (an unknown field is a problem at its path, not silently dropped) and bounded: at
 * most {@link #MAX_CHARS} characters, one YAML document, no YAML anchors or aliases (the "billion laughs" expansion
 * needs them, and a definition has no use for them), no YAML tags. Values are checked by {@link DefinitionValidator},
 * not here.
 */
public final class DefinitionCodec {

    /** A definition is configuration, not data: 256 KB is generous (a sample step's inline rows included). */
    public static final int MAX_CHARS = 256 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

    /**
     * The YAML side is snakeyaml's, not Jackson's: jackson-dataformat-yaml 2.11 (the Spring Boot parent's) reads a
     * quoted "007" as the number 7 into an untyped config value, which would change what a person wrote. Read with the
     * safe constructor and a resolver that knows no timestamps (a config's "2026-09-28" stays the text it was) and
     * no merge keys; written with the standard resolver, so anything another YAML reader would take for a date, a
     * number or true is quoted.
     */
    private static Yaml reader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        // The constructor is given the same options (SnakeYAML 1.33 deprecates the bare ones; 2.x has no others).
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, new PlainResolver());
    }

    private static Yaml writer() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(120);
        options.setSplitLines(false);
        return new Yaml(options);
    }

    /** YAML 1.1's implicit types, less timestamps and merge keys. */
    private static final class PlainResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            this.addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            this.addImplicitResolver(Tag.INT, INT, "-+0123456789");
            this.addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            this.addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            this.addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }

    private DefinitionCodec() {
    }

    public static PipelineDefinition fromJson(String json) throws DefinitionException {
        bounded(json);
        try {
            return nonNull(JSON.readValue(json, PipelineDefinition.class));
        } catch (JsonProcessingException ex) {
            throw new DefinitionException(problemOf(ex, "JSON"));
        }
    }

    public static PipelineDefinition fromYaml(String yaml) throws DefinitionException {
        bounded(yaml);
        plainYaml(yaml);
        Object tree;
        try {
            tree = reader().load(yaml);
        } catch (RuntimeException ex) {
            throw new DefinitionException(new DefinitionProblem("$", "not valid YAML: " + firstLine(ex.getMessage())));
        }
        if (tree == null) {
            throw new DefinitionException(new DefinitionProblem("$", "the definition is empty"));
        }
        if (!(tree instanceof Map)) {
            throw new DefinitionException(new DefinitionProblem("$", "a definition is an object (version, source, steps, settings)"));
        }
        try {
            return nonNull(JSON.convertValue(tree, PipelineDefinition.class));
        } catch (IllegalArgumentException ex) {
            if (ex.getCause() instanceof JsonProcessingException) {
                throw new DefinitionException(problemOf((JsonProcessingException) ex.getCause(), "YAML"));
            }
            throw new DefinitionException(new DefinitionProblem("$", "not valid YAML: " + firstLine(ex.getMessage())));
        }
    }

    /** Reads either: a document that starts with '{' is JSON, anything else YAML (JSON is YAML too, but not strictly). */
    public static PipelineDefinition read(String text, String format) throws DefinitionException {
        if ("yaml".equalsIgnoreCase(format) || "yml".equalsIgnoreCase(format)) {
            return fromYaml(text);
        }
        if ("json".equalsIgnoreCase(format)) {
            return fromJson(text);
        }
        return text != null && text.trim().startsWith("{") ? fromJson(text) : fromYaml(text);
    }

    /** The stored form: compact JSON, fields in the definition's own order. */
    public static String toJson(PipelineDefinition definition) {
        try {
            return JSON.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A pipeline definition could not be written as JSON.", ex);
        }
    }

    /** The console's JSON tab: the same, indented. */
    public static String toPrettyJson(PipelineDefinition definition) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A pipeline definition could not be written as JSON.", ex);
        }
    }

    /** The console's YAML tab. */
    public static String toYaml(PipelineDefinition definition) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = JSON.convertValue(definition, LinkedHashMap.class);
        return writer().dump(tree);
    }

    private static void bounded(String text) throws DefinitionException {
        if (text == null || text.trim().isEmpty()) {
            throw new DefinitionException(new DefinitionProblem("$", "the definition is empty"));
        }
        if (text.length() > MAX_CHARS) {
            throw new DefinitionException(new DefinitionProblem("$", String.format(
                "the definition is %d characters; at most %d are accepted", text.length(), MAX_CHARS)));
        }
    }

    private static PipelineDefinition nonNull(PipelineDefinition definition) throws DefinitionException {
        if (definition == null) {
            throw new DefinitionException(new DefinitionProblem("$", "the definition is empty"));
        }
        return definition;
    }

    /** One document, no anchors, no aliases, no tags -- checked on snakeyaml's events, before anything is built. */
    private static void plainYaml(String yaml) throws DefinitionException {
        int documents = 0;
        try {
            for (Event event : new Yaml().parse(new StringReader(yaml))) {
                if (event instanceof AliasEvent) {
                    throw new DefinitionException(new DefinitionProblem("$", "YAML aliases (*name) are not accepted in a definition"));
                }
                if (event instanceof DocumentStartEvent && ++documents > 1) {
                    throw new DefinitionException(new DefinitionProblem("$", "a definition is one YAML document; found more than one"));
                }
                String anchor = event instanceof ScalarEvent ? ((ScalarEvent) event).getAnchor()
                    : event instanceof MappingStartEvent ? ((MappingStartEvent) event).getAnchor()
                    : event instanceof SequenceStartEvent ? ((SequenceStartEvent) event).getAnchor() : null;
                if (anchor != null) {
                    throw new DefinitionException(new DefinitionProblem("$", "YAML anchors (&name) are not accepted in a definition"));
                }
                String tag = event instanceof ScalarEvent ? ((ScalarEvent) event).getTag()
                    : event instanceof MappingStartEvent ? ((MappingStartEvent) event).getTag()
                    : event instanceof SequenceStartEvent ? ((SequenceStartEvent) event).getTag() : null;
                if (tag != null) {
                    throw new DefinitionException(new DefinitionProblem("$", "YAML tags (!name) are not accepted in a definition"));
                }
            }
        } catch (DefinitionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DefinitionException(new DefinitionProblem("$", "not valid YAML: " + firstLine(ex.getMessage())));
        }
    }

    private static DefinitionProblem problemOf(JsonProcessingException ex, String format) {
        String path = ex instanceof JsonMappingException ? pathOf(((JsonMappingException) ex).getPath()) : "$";
        if (ex instanceof UnrecognizedPropertyException) {
            UnrecognizedPropertyException unknown = (UnrecognizedPropertyException) ex;
            String parent = pathOf(unknown.getPath().subList(0, Math.max(0, unknown.getPath().size() - 1)));
            return new DefinitionProblem(parent, String.format("unknown field '%s'", unknown.getPropertyName()));
        }
        if (ex instanceof InvalidFormatException) {
            return new DefinitionProblem(path, String.format("'%s' is not %s", ((InvalidFormatException) ex).getValue(),
                describe(((InvalidFormatException) ex).getTargetType())));
        }
        if (ex instanceof MismatchedInputException) {
            Class<?> target = ((MismatchedInputException) ex).getTargetType();
            return new DefinitionProblem(path, target == null ? "a value of the wrong shape" : "expected " + describe(target));
        }
        if (ex.getLocation() != null) {
            return new DefinitionProblem("$", String.format("not valid %s at line %d, column %d: %s", format,
                ex.getLocation().getLineNr(), ex.getLocation().getColumnNr(), firstLine(ex.getOriginalMessage())));
        }
        return new DefinitionProblem(path, String.format("not valid %s: %s", format, firstLine(ex.getOriginalMessage())));
    }

    private static String describe(Class<?> type) {
        if (type == null) {
            return "the right type";
        }
        if (Integer.class.equals(type) || int.class.equals(type) || Long.class.equals(type) || long.class.equals(type)) {
            return "a whole number";
        }
        if (String.class.equals(type)) {
            return "text";
        }
        if (List.class.isAssignableFrom(type)) {
            return "a list";
        }
        if (Map.class.isAssignableFrom(type) || type.getName().startsWith("process.pipeline")) {
            return "an object";
        }
        return "a " + type.getSimpleName();
    }

    static String pathOf(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                path.append(path.length() == 0 ? "" : ".").append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.length() == 0 ? "$" : path.toString();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
