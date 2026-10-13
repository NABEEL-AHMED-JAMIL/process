package process.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

/**
 * MIG-347: a pipeline's run parameters -- what a pipeline declares (PipelineDefinition.Parameter), what a run is given
 * (Run with..., a backfill, the API), and how every step reads them: {{param.name}}, and the run's own {{run.date}},
 * {{run.windowStart}} and {{run.windowEnd}}, filled into the step's settings before the step sees them.
 *
 * <ul>
 *   <li><b>Types</b>: date (2026-10-01), datetime (2026-10-01T09:30:00, a wall-clock time in the workspace's billing
 *   zone), text (at most {@value #MAX_TEXT} characters, never "{{"), number (a decimal), boolean (true or false) and
 *   choice (one of the parameter's choices). A value is normalised to one text form, which is what a step is given.</li>
 *   <li><b>Values</b>: a given value is checked against its parameter's type; a parameter not given takes its default; a
 *   required parameter with no default and no value is refused; a name the pipeline does not declare is refused.</li>
 *   <li><b>run.*</b>: run.date is the day the run is for (a scheduled run's slot's day, a backfill run's period's, else
 *   today) in the workspace's billing time zone; run.windowStart and run.windowEnd bound its period, as
 *   2026-10-01T00:00:00-05:00 -- an instant with the zone's offset.</li>
 * </ul>
 * Nothing here reads a clock or a database: the callers give it the day, the zone and the period.
 */
public final class RunParameters {

    public static final String DATE = "date";
    public static final String DATETIME = "datetime";
    public static final String TEXT = "text";
    public static final String NUMBER = "number";
    public static final String BOOLEAN = "boolean";
    public static final String CHOICE = "choice";
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(DATE, DATETIME, TEXT, NUMBER, BOOLEAN, CHOICE));

    public static final int MAX_PARAMETERS = 20;
    public static final int MAX_TEXT = 500;
    public static final int MAX_CHOICES = 50;
    public static final int MAX_CHOICE_LENGTH = 100;
    public static final int MAX_LABEL = 120;
    public static final int MAX_DESCRIPTION = 500;

    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    /** {{param.name}}, {{run.date}}, {{run.windowStart}}, {{run.windowEnd}}: what {@link #fill} replaces; nothing else. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*((?:param\\.[a-z][a-z0-9_]{0,39})|run\\.date|run\\.windowStart|run\\.windowEnd)\\s*}}");
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
    private static final ObjectMapper JSON = new ObjectMapper();

    private RunParameters() {
    }

    // ------------------------------------------------------------------------------------------------- declarations

    /** The problems of a pipeline's declared parameters, each at its path (parameters[i].field). */
    public static List<DefinitionProblem> problems(List<PipelineDefinition.Parameter> parameters) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (parameters == null) {
            return problems;
        }
        if (parameters.size() > MAX_PARAMETERS) {
            problems.add(new DefinitionProblem("parameters", "at most " + MAX_PARAMETERS + " parameters"));
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < parameters.size(); i++) {
            String at = "parameters[" + i + "]";
            PipelineDefinition.Parameter p = parameters.get(i);
            if (p == null) {
                problems.add(new DefinitionProblem(at, "a parameter is an object"));
                continue;
            }
            if (p.getName() == null || !NAME.matcher(p.getName()).matches()) {
                problems.add(new DefinitionProblem(at + ".name", "lower case letters, digits and '_', starting with a letter, at most 40"));
            } else if (!names.add(p.getName())) {
                problems.add(new DefinitionProblem(at + ".name", String.format("'%s' is already the name of an earlier parameter", p.getName())));
            }
            if (p.getType() == null || !TYPES.contains(p.getType())) {
                problems.add(new DefinitionProblem(at + ".type", "one of " + TYPES));
                continue;
            }
            if (p.getLabel() != null && p.getLabel().length() > MAX_LABEL) {
                problems.add(new DefinitionProblem(at + ".label", "at most " + MAX_LABEL + " characters"));
            }
            if (p.getDescription() != null && p.getDescription().length() > MAX_DESCRIPTION) {
                problems.add(new DefinitionProblem(at + ".description", "at most " + MAX_DESCRIPTION + " characters"));
            }
            if (CHOICE.equals(p.getType())) {
                List<String> choices = p.getChoices();
                if (choices == null || choices.isEmpty() || choices.size() > MAX_CHOICES) {
                    problems.add(new DefinitionProblem(at + ".choices", "a choice parameter has 1 to " + MAX_CHOICES + " choices"));
                } else {
                    Set<String> seen = new HashSet<>();
                    for (int c = 0; c < choices.size(); c++) {
                        String choice = choices.get(c);
                        if (choice == null || choice.trim().isEmpty() || choice.length() > MAX_CHOICE_LENGTH || choice.contains("{{")) {
                            problems.add(new DefinitionProblem(at + ".choices[" + c + "]", "1 to " + MAX_CHOICE_LENGTH + " characters, no '{{'"));
                        } else if (!seen.add(choice)) {
                            problems.add(new DefinitionProblem(at + ".choices[" + c + "]", String.format("'%s' is already a choice", choice)));
                        }
                    }
                }
            } else if (p.getChoices() != null) {
                problems.add(new DefinitionProblem(at + ".choices", "only a choice parameter has choices"));
            }
            if (p.getDefaultValue() != null) {
                try {
                    normalise(p, p.getDefaultValue());
                } catch (IllegalArgumentException wrong) {
                    problems.add(new DefinitionProblem(at + ".default", wrong.getMessage()));
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------------------- values

    /** The values a run takes, or why it cannot: each refusal names its parameter. */
    public static final class Resolved {
        /** Every declared parameter's value, normalised, in declared order (a parameter with no value is left out). */
        public final Map<String, String> values;
        /** One line per problem: "region: one of north, south". Empty when the values stand. */
        public final List<String> problems;

        Resolved(Map<String, String> values, List<String> problems) {
            this.values = Collections.unmodifiableMap(values);
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean ok() {
            return this.problems.isEmpty();
        }

        /** The problems as one sentence for an answer or a run's status line. */
        public String sentence() {
            return String.join("; ", this.problems) + ".";
        }
    }

    /**
     * The values a run of a pipeline with these parameters takes when it is given {@code given} (null: none, as a scheduled
     * run): each given value checked and normalised, each other parameter its default; a required one with neither is a
     * problem, and so is a name the pipeline does not declare.
     */
    public static Resolved resolve(List<PipelineDefinition.Parameter> declared, Map<String, ?> given) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, PipelineDefinition.Parameter> byName = new LinkedHashMap<>();
        for (PipelineDefinition.Parameter p : declared == null ? Collections.<PipelineDefinition.Parameter>emptyList() : declared) {
            if (p != null && p.getName() != null) {
                byName.put(p.getName(), p);
            }
        }
        if (given != null) {
            for (String name : given.keySet()) {
                if (!byName.containsKey(name)) {
                    problems.add(String.format("%s: this pipeline has no such parameter", name));
                }
            }
        }
        for (PipelineDefinition.Parameter p : byName.values()) {
            Object value = given == null ? null : given.get(p.getName());
            boolean blank = value == null || (value instanceof String && ((String) value).trim().isEmpty() && !TEXT.equals(p.getType()));
            if (blank) {
                value = p.getDefaultValue();
            }
            if (value == null) {
                if (p.required()) {
                    problems.add(String.format("%s: required, and it has no default", p.getName()));
                }
                continue;
            }
            try {
                values.put(p.getName(), normalise(p, value));
            } catch (IllegalArgumentException wrong) {
                problems.add(p.getName() + ": " + wrong.getMessage());
            }
        }
        return new Resolved(values, problems);
    }

    /** One value in its parameter's one text form; an IllegalArgumentException says what the type wants. */
    static String normalise(PipelineDefinition.Parameter p, Object value) {
        String type = p.getType() == null ? TEXT : p.getType();
        String text = value instanceof String ? ((String) value).trim() : String.valueOf(value);
        switch (type) {
            case DATE:
                try {
                    return LocalDate.parse(text).toString();
                } catch (DateTimeParseException wrong) {
                    throw new IllegalArgumentException("a date, as 2026-10-01");
                }
            case DATETIME:
                try {
                    LocalDateTime at = text.length() == 16 ? LocalDateTime.parse(text + ":00") : LocalDateTime.parse(text);
                    return at.truncatedTo(ChronoUnit.SECONDS).format(WALL);
                } catch (DateTimeParseException wrong) {
                    throw new IllegalArgumentException("a date and time, as 2026-10-01T09:30");
                }
            case NUMBER:
                if (value instanceof Boolean) {
                    throw new IllegalArgumentException("a number");
                }
                try {
                    BigDecimal number = new BigDecimal(text);
                    if (number.precision() > 30 || Math.abs(number.scale()) > 15) {
                        throw new IllegalArgumentException("a number of at most 30 digits");
                    }
                    String plain = number.stripTrailingZeros().toPlainString();
                    return "-0".equals(plain) ? "0" : plain;
                } catch (NumberFormatException wrong) {
                    throw new IllegalArgumentException("a number");
                }
            case BOOLEAN:
                if (value instanceof Boolean) {
                    return value.toString();
                }
                if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                    return text.toLowerCase(Locale.ROOT);
                }
                throw new IllegalArgumentException("true or false");
            case CHOICE:
                if (p.getChoices() == null || !p.getChoices().contains(text)) {
                    throw new IllegalArgumentException("one of " + (p.getChoices() == null ? "its choices" : String.join(", ", p.getChoices())));
                }
                return text;
            case TEXT:
            default:
                if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                    throw new IllegalArgumentException("text");
                }
                String raw = value instanceof String ? (String) value : text;
                if (raw.length() > MAX_TEXT) {
                    throw new IllegalArgumentException("at most " + MAX_TEXT + " characters");
                }
                if (raw.contains("{{")) {
                    throw new IllegalArgumentException("text without '{{'");
                }
                for (int i = 0; i < raw.length(); i++) {
                    char c = raw.charAt(i);
                    if (c < 0x20 && c != '\t') {
                        throw new IllegalArgumentException("one line of text");
                    }
                }
                return raw;
        }
    }

    // ---------------------------------------------------------------------------------------------------- run values

    /** What a run reads as {{param.*}} and {{run.*}}: its parameter values and the day and period it is for. */
    public static final class RunValues {
        public final Map<String, String> params;
        public final String date;
        public final String windowStart;
        public final String windowEnd;
        public final String zone;

        public RunValues(Map<String, String> params, String date, String windowStart, String windowEnd, String zone) {
            this.params = params == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
            this.date = date;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.zone = zone;
        }

        /** The day {@code day} in {@code zone}: its first instant to the next day's. */
        public static RunValues ofDay(Map<String, String> params, LocalDate day, ZoneId zone) {
            return new RunValues(params, day.toString(), stamp(day.atStartOfDay(zone)), stamp(day.plusDays(1).atStartOfDay(zone)), zone.getId());
        }

        /** The period [start, end) whose day (in {@code zone}) is {@code day}. */
        public static RunValues ofPeriod(Map<String, String> params, LocalDate day, Instant start, Instant end, ZoneId zone) {
            return new RunValues(params, day.toString(), stamp(start.atZone(zone)), stamp(end.atZone(zone)), zone.getId());
        }

        /** The hour of {@code at} in {@code zone} (a local hour: Asia/Kolkata's start at :30 UTC). */
        public static RunValues ofHour(Map<String, String> params, Instant at, ZoneId zone) {
            ZonedDateTime start = at.atZone(zone).truncatedTo(ChronoUnit.HOURS);
            return new RunValues(params, start.toLocalDate().toString(), stamp(start), stamp(start.plusHours(1)), zone.getId());
        }

        /** {{param.name}} and {{run.*}} by placeholder name. */
        public Map<String, Object> placeholders() {
            Map<String, Object> out = new LinkedHashMap<>();
            this.params.forEach((name, value) -> out.put("param." + name, value));
            if (this.date != null) {
                out.put("run.date", this.date);
            }
            if (this.windowStart != null) {
                out.put("run.windowStart", this.windowStart);
            }
            if (this.windowEnd != null) {
                out.put("run.windowEnd", this.windowEnd);
            }
            return out;
        }

        /** As job_queue.run_params holds it. */
        public String toJson() {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode p = root.putObject("params");
            this.params.forEach(p::put);
            if (this.date != null) {
                ObjectNode run = root.putObject("run");
                run.put("date", this.date);
                run.put("windowStart", this.windowStart);
                run.put("windowEnd", this.windowEnd);
                run.put("zone", this.zone);
            }
            try {
                return JSON.writeValueAsString(root);
            } catch (JsonProcessingException cannot) {
                throw new IllegalStateException(cannot);
            }
        }

        @Override
        public String toString() {
            return this.toJson();
        }
    }

    /** What a run's run_params holds: its given values and, for a backfill run, its run.* values. */
    public static final class Stored {
        /** The values the run was given (already normalised when they were written); empty when none. */
        public final Map<String, String> params;
        /** The run.* values written with it (a backfill run); null when the engine works them out. */
        public final RunValues run;

        Stored(Map<String, String> params, RunValues run) {
            this.params = params;
            this.run = run;
        }

        public static final Stored NONE = new Stored(Collections.<String, String>emptyMap(), null);
    }

    /** Reads job_queue.run_params; text that is not ours reads as nothing given (the run then takes the defaults). */
    public static Stored read(String runParams) {
        if (runParams == null || runParams.trim().isEmpty()) {
            return Stored.NONE;
        }
        try {
            JsonNode root = JSON.readTree(runParams);
            Map<String, String> params = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.path("params").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                params.put(field.getKey(), field.getValue().isNull() ? null : field.getValue().asText());
            }
            JsonNode run = root.path("run");
            RunValues values = run.isObject() && run.hasNonNull("date")
                ? new RunValues(params, run.path("date").asText(), text(run, "windowStart"), text(run, "windowEnd"), text(run, "zone"))
                : null;
            return new Stored(Collections.unmodifiableMap(params), values);
        } catch (JsonProcessingException unreadable) {
            return Stored.NONE;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String stamp(ZonedDateTime at) {
        return at.format(WINDOW);
    }

    // -------------------------------------------------------------------------------------------------- substitution

    /**
     * A step's settings with {{param.*}} and {{run.*}} filled in, deep: every text value, in maps and lists, at any depth.
     * Every other placeholder ({{run}}, {{date}}, {{column}}, ...) is left for the step, which fills its own. A placeholder
     * whose parameter has no value is left as written, so the mistake shows in the result. In a field the task declares as
     * SQL ({@code sqlFields}), a text value's single quotes are doubled -- the value is meant to go inside '...' -- and a
     * backslash is refused, since not every database reads it the same way.
     *
     * Returns {@code config} itself when nothing in it names a run value, so a pipeline without parameters costs a scan.
     */
    public static Map<String, Object> fill(Map<String, Object> config, Map<String, Object> values, Collection<String> sqlFields) {
        if (config == null || config.isEmpty() || values == null || values.isEmpty() || !names(config)) {
            return config;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            boolean sql = sqlFields != null && sqlFields.contains(entry.getKey());
            out.put(entry.getKey(), fillValue(entry.getValue(), values, sql));
        }
        return out;
    }

    /** The parameter names {{param.name}} placeholders in {@code value} name, at any depth. */
    public static Set<String> namedParameters(Object value) {
        Set<String> out = new TreeSet<>();
        collect(value, out);
        return out;
    }

    private static void collect(Object value, Set<String> out) {
        if (value instanceof String) {
            Matcher matcher = PLACEHOLDER.matcher((String) value);
            while (matcher.find()) {
                if (matcher.group(1).startsWith("param.")) {
                    out.add(matcher.group(1).substring("param.".length()));
                }
            }
        } else if (value instanceof Map) {
            ((Map<?, ?>) value).values().forEach(v -> collect(v, out));
        } else if (value instanceof Collection) {
            ((Collection<?>) value).forEach(v -> collect(v, out));
        }
    }

    /** Whether any text in {@code value} holds a run-value placeholder. */
    private static boolean names(Object value) {
        if (value instanceof String) {
            return ((String) value).contains("{{") && PLACEHOLDER.matcher((String) value).find();
        }
        if (value instanceof Map) {
            for (Object v : ((Map<?, ?>) value).values()) {
                if (names(v)) {
                    return true;
                }
            }
        } else if (value instanceof Collection) {
            for (Object v : (Collection<?>) value) {
                if (names(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Object fillValue(Object value, Map<String, Object> values, boolean sql) {
        if (value instanceof String) {
            return fillText((String) value, values, sql);
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> out.put(k, fillValue(v, values, sql)));
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<Object>) value) {
                out.add(fillValue(v, values, sql));
            }
            return out;
        }
        return value;
    }

    /** One text with its run-value placeholders filled in. */
    public static String fillText(String text, Map<String, Object> values, boolean sql) {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            if (value == null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = String.valueOf(value);
            if (sql) {
                if (replacement.indexOf('\\') >= 0) {
                    throw new IllegalArgumentException(String.format("The value of {{%s}} has a backslash, which a query cannot be given.",
                        matcher.group(1)));
                }
                replacement = replacement.replace("'", "''");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The top-level settings a task's config schema marks as SQL ({"format": "sql"}). */
    @SuppressWarnings("unchecked")
    public static Set<String> sqlFields(Map<String, Object> configSchema) {
        Set<String> out = new HashSet<>();
        Object properties = configSchema == null ? null : configSchema.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<String, Object> property : ((Map<String, Object>) properties).entrySet()) {
                if (property.getValue() instanceof Map && "sql".equals(((Map<String, Object>) property.getValue()).get("format"))) {
                    out.add(property.getKey());
                }
            }
        }
        return out;
    }
}
