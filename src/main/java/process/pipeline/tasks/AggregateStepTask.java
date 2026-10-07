package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate (MIG-231): one row per group of equal {@code groupBy} values (in the order groups first appear; one row
 * for all when none), with each aggregation as a column: count (rows), count_distinct, sum, avg, min, max, first,
 * last. sum and avg read numbers (numeric text too) and skip nulls; a value that is not a number fails the step at its
 * row. min and max compare numbers by value, else text. Pure Core, at most {@value Limits#MAX_ROWS} groups.
 */
@Component
public class AggregateStepTask extends RegisteredTask {

    static final List<String> OPS = Arrays.asList("count", "count_distinct", "sum", "avg", "min", "max", "first", "last", "list");

    /** list: at most this many distinct values are named; the rest are counted ("+3 more"). */
    static final int LIST_MAX = 50;

    static final TaskSpec SPEC = TaskSpec.builder("aggregate", "Aggregate", TaskKind.PROCESS)
        .description("Groups rows by columns and computes count, count_distinct, sum, avg, min, max, first, last or list"
            + " (the group's distinct values as one text, in the order first seen, joined by \", \").")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("One row per group: the group's columns, then each aggregation."))
        .config(JsonSchema.object()
            .property("groupBy", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column")).maxItems(20)
                .title("Group by").description("Empty: one row for all the input."))
            .required("aggregations", JsonSchema.array(JsonSchema.object()
                .required("op", JsonSchema.string().enumOf(OPS.toArray(new String[0])).title("Compute"))
                .property("column", JsonSchema.string().maxLength(128).title("Of column").format("column")
                    .description("Every operation but count needs one."))
                .required("as", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .minItems(1).maxItems(50).title("Aggregations")))
        .aiToolName("aggregate_rows")
        .build();

    public AggregateStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>(Configs.texts(config, "groupBy"));
        List<Map<String, Object>> aggregations = Configs.objects(config, "aggregations");
        for (int i = 0; i < aggregations.size(); i++) {
            Map<String, Object> aggregation = aggregations.get(i);
            if (!"count".equals(aggregation.get("op")) && Configs.text(aggregation, "column", null) == null) {
                problems.add(new DefinitionProblem("aggregations[" + i + "].column", aggregation.get("op") + " needs its column"));
            }
            String as = Configs.text(aggregation, "as", "");
            if (!names.add(as)) {
                problems.add(new DefinitionProblem("aggregations[" + i + "].as", String.format("'%s' is already a column of the output", as)));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) {
        List<String> groupBy = Configs.texts(context.config(), "groupBy");
        List<Map<String, Object>> aggregations = Configs.objects(context.config(), "aggregations");
        Dataset input = context.input();
        Map<List<String>, Group> groups = new LinkedHashMap<>();
        int index = 0;
        for (Map<String, Object> row : input.getRows()) {
            index++;
            List<String> key = new ArrayList<>(groupBy.size());
            for (String column : groupBy) {
                String part = Values.key(row.get(column));
                key.add(part == null ? "null" : part);
            }
            Group group = groups.get(key);
            if (group == null) {
                if (groups.size() >= Limits.MAX_ROWS) {
                    throw new IllegalStateException(String.format("More than %,d groups, the most a step holds.", Limits.MAX_ROWS));
                }
                group = new Group(row, groupBy, aggregations.size());
                groups.put(key, group);
            }
            group.add(row, aggregations, index);
        }
        if (groupBy.isEmpty() && groups.isEmpty()) {
            groups.put(new ArrayList<>(), new Group(new LinkedHashMap<>(), groupBy, aggregations.size()));
        }
        Set<String> columns = new LinkedHashSet<>(groupBy);
        for (Map<String, Object> aggregation : aggregations) {
            columns.add(Configs.text(aggregation, "as", ""));
        }
        List<Map<String, Object>> rows = new ArrayList<>(groups.size());
        for (Group group : groups.values()) {
            rows.add(group.result(aggregations));
        }
        context.log(String.format("%d row(s) in %d group(s).", input.size(), rows.size()));
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** One group's running values: one slot per aggregation. */
    private static final class Group {
        private final Map<String, Object> keys = new LinkedHashMap<>();
        private final long[] counts;
        private final BigDecimal[] sums;
        private final Object[] picks;
        private final List<java.util.LinkedHashSet<String>> lists = new ArrayList<>();
        private final List<Set<String>> distinct = new ArrayList<>();
        private long rows;

        Group(Map<String, Object> first, List<String> groupBy, int slots) {
            for (String column : groupBy) {
                this.keys.put(column, first.get(column));
            }
            this.counts = new long[slots];
            this.sums = new BigDecimal[slots];
            this.picks = new Object[slots];
            for (int i = 0; i < slots; i++) {
                this.distinct.add(null);
                this.lists.add(null);
            }
        }

        void add(Map<String, Object> row, List<Map<String, Object>> aggregations, int index) {
            this.rows++;
            for (int i = 0; i < aggregations.size(); i++) {
                String op = Configs.text(aggregations.get(i), "op", "count");
                String column = Configs.text(aggregations.get(i), "column", "");
                Object value = row.get(column);
                switch (op) {
                    case "count":
                        break;
                    case "count_distinct":
                        if (value != null) {
                            if (this.distinct.get(i) == null) {
                                this.distinct.set(i, new HashSet<>());
                            }
                            this.distinct.get(i).add(Values.key(value));
                        }
                        break;
                    case "sum":
                    case "avg":
                        if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
                            BigDecimal number = Values.number(value);
                            if (number == null) {
                                throw new IllegalArgumentException(String.format("Row %d: %s is not a number (%s).", index, column, op));
                            }
                            this.sums[i] = this.sums[i] == null ? number : this.sums[i].add(number);
                            this.counts[i]++;
                        }
                        break;
                    case "min":
                        if (value != null && (this.picks[i] == null || Values.compare(value, this.picks[i]) < 0)) {
                            this.picks[i] = value;
                        }
                        break;
                    case "max":
                        if (value != null && (this.picks[i] == null || Values.compare(value, this.picks[i]) > 0)) {
                            this.picks[i] = value;
                        }
                        break;
                    case "list":
                        if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
                            if (this.lists.get(i) == null) {
                                this.lists.set(i, new java.util.LinkedHashSet<>());
                            }
                            this.lists.get(i).add(Values.text(value).trim());
                        }
                        break;
                    case "first":
                        if (this.counts[i]++ == 0) {
                            this.picks[i] = value;
                        }
                        break;
                    case "last":
                    default:
                        this.picks[i] = value;
                        break;
                }
            }
        }

        Map<String, Object> result(List<Map<String, Object>> aggregations) {
            Map<String, Object> out = new LinkedHashMap<>(this.keys);
            for (int i = 0; i < aggregations.size(); i++) {
                String op = Configs.text(aggregations.get(i), "op", "count");
                Object value;
                switch (op) {
                    case "count":
                        value = this.rows;
                        break;
                    case "count_distinct":
                        value = (long) (this.distinct.get(i) == null ? 0 : this.distinct.get(i).size());
                        break;
                    case "sum":
                        value = Values.plain(this.sums[i] == null ? BigDecimal.ZERO : this.sums[i]);
                        break;
                    case "list":
                        value = listed(this.lists.get(i));
                        break;
                    case "avg":
                        value = this.counts[i] == 0 ? null : Values.plain(this.sums[i].divide(BigDecimal.valueOf(this.counts[i]), MathContext.DECIMAL64));
                        break;
                    default:
                        value = this.picks[i];
                }
                out.put(Configs.text(aggregations.get(i), "as", ""), value);
            }
            return out;
        }

        private static String listed(java.util.Set<String> values) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            int shown = 0;
            for (String value : values) {
                if (shown == LIST_MAX) {
                    break;
                }
                text.append(shown++ == 0 ? "" : ", ").append(value);
            }
            if (values.size() > LIST_MAX) {
                text.append(" (+").append(values.size() - LIST_MAX).append(" more)");
            }
            return text.toString();
        }
    }
}
