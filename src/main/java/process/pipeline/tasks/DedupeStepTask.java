package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remove duplicates (owner #16, 2026-10-19): one row per key -- the columns it names, or the whole row -- keeping the first
 * or the last of each, in the input's order. Streamed: it holds a 128-bit fingerprint per distinct key (charged to the
 * run's memory budget at {@value #BYTES_PER_KEY} bytes each), never the rows; keep "last" reads its input twice. Past the
 * budget the step fails saying so, rather than holding more than the run may.
 */
@Component
public class DedupeStepTask extends RegisteredTask implements StreamingStepTask {

    /** What one remembered key costs in a hash set (the fingerprint, its node and its slot), as the budget counts it. */
    static final long BYTES_PER_KEY = 96;

    static final TaskSpec SPEC = TaskSpec.builder("dedupe", "Remove duplicates", TaskKind.PROCESS)
        .description("Keeps one row per key -- the columns you name, or the whole row -- the first or the last of each, in the input's "
            + "order.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The same columns, one row per key."))
        .config(JsonSchema.object()
            .property("by", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column")).maxItems(50)
                .title("Key columns").description("Rows equal on these columns are duplicates. Empty: rows equal on every column."))
            .property("keep", JsonSchema.string().enumOf("first", "last").title("Keep").defaultValue("first")
                .description("first: the earliest row of each key; last: the latest."))
            .property("ignoreCase", JsonSchema.bool().title("Ignore case").defaultValue(false)
                .description("Treat text that differs only in upper and lower case as equal (and spaces around it).")))
        .backing(TaskSpec.CORE)
        .timeoutSeconds(1800)
        .aiToolName("remove_duplicates")
        .items(ItemRole.SHARED).build();

    public DedupeStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "by", "keep", "ignoreCase");
        Set<String> seen = new HashSet<>();
        List<String> by = Configs.texts(config, "by");
        for (int i = 0; i < by.size(); i++) {
            if (by.get(i).isEmpty()) {
                problems.add(new DefinitionProblem("by[" + i + "]", "a column name is text"));
            } else if (!seen.add(by.get(i))) {
                problems.add(new DefinitionProblem("by[" + i + "]", String.format("'%s' is named twice", by.get(i))));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        List<String> by = Configs.texts(context.config(), "by");
        boolean last = "last".equals(Configs.text(context.config(), "keep", "first"));
        boolean ignoreCase = Configs.bool(context.config(), "ignoreCase", false);
        RowSink out = context.output();
        RunMemory memory = context.memory();
        long held = 0;
        long rows = 0;
        try {
            // keep last: the first pass learns each key's last row number; the second keeps exactly those rows.
            Map<UUID, Long> lastRow = last ? new HashMap<>() : null;
            Set<UUID> seen = last ? null : new HashSet<>();
            if (last) {
                try (RowSource input = context.openInput()) {
                    requireColumns(by, input.columns());
                    long n = 0;
                    for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                        stopped();
                        for (Map<String, Object> row : batch) {
                            UUID key = fingerprint(row, by, input.columns(), ignoreCase);
                            if (lastRow.put(key, n++) == null) {
                                held = this.charge(memory, held, context);
                            }
                        }
                    }
                }
            }
            try (RowSource input = context.openInput()) {
                requireColumns(by, input.columns());
                out.declare(input.columns());
                long n = 0;
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    stopped();
                    for (Map<String, Object> row : batch) {
                        rows++;
                        UUID key = fingerprint(row, by, input.columns(), ignoreCase);
                        boolean keepIt;
                        if (last) {
                            keepIt = lastRow.get(key) == n;
                        } else {
                            keepIt = seen.add(key);
                            if (keepIt) {
                                held = this.charge(memory, held, context);
                            }
                        }
                        n++;
                        if (keepIt) {
                            out.add(row);
                        }
                    }
                }
            }
        } finally {
            memory.release(held);
        }
        context.log(String.format("%,d row(s) in, %,d kept, %,d duplicate(s) removed (key: %s; kept the %s of each).", rows, out.size(),
            rows - out.size(), by.isEmpty() ? "every column" : String.join(", ", by), last ? "last" : "first"));
        return StepResult.streamed(out.size());
    }

    /** One more key's bytes from the budget, or the step fails saying why. */
    private long charge(RunMemory memory, long held, StreamContext context) {
        if (!memory.tryReserve(BYTES_PER_KEY)) {
            throw new IllegalStateException(String.format("Remove duplicates holds one fingerprint per distinct key, and %,d keys passed the "
                + "run's memory budget (%s). Name fewer key columns, split the input, or ask for a larger budget.", held / BYTES_PER_KEY,
                RunMemory.megabytes(memory.budget())));
        }
        return held + BYTES_PER_KEY;
    }

    static void requireColumns(List<String> by, List<String> has) {
        List<String> missing = new ArrayList<>();
        for (String column : by) {
            if (!has.contains(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The input has no column " + String.join(", ", missing) + ".");
        }
    }

    /**
     * The key's 128-bit fingerprint: SHA-256 of each value as a join key ({@link Values#key}: 1, 1.0 and "1" are one value,
     * "001" is not), in order. Empty and missing are the same.
     */
    static UUID fingerprint(Map<String, Object> row, List<String> by, List<String> columns, boolean ignoreCase) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        List<String> keys = by.isEmpty() ? columns : by;
        for (String column : keys) {
            Object value = row.get(column);
            if (ignoreCase && value instanceof String) {
                value = ((String) value).trim().toLowerCase(Locale.ROOT);
            }
            String text = value == null || "".equals(value) ? "" : Values.key(value);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            sha.update(bytes);
        }
        ByteBuffer digest = ByteBuffer.wrap(sha.digest());
        return new UUID(digest.getLong(), digest.getLong());
    }

    private static void stopped() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Remove duplicates was stopped.");
        }
    }
}
