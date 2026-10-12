package process.pipeline.tasks;

import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.StreamContext;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * MIG-365 / MIG-346: a streaming try as the engine gives one -- its input and other steps' outputs a batch at a time
 * (counted, to see what a step read), a budget of its own, a real scratch directory, and a sink that is not held to the
 * in-memory bounds (it checks each row with {@link #check}, or keeps them when {@link #keep} is on).
 */
final class StreamTry implements StreamContext {

    final Map<String, Object> config;
    final Dataset input;
    final Map<String, Dataset> outputs = new LinkedHashMap<>();
    final RunMemory memory;
    final Path dir;
    final int batch;
    final List<String> lines = new ArrayList<>();
    final List<String> declared = new ArrayList<>();
    final List<Map<String, Object>> rows = new ArrayList<>();
    boolean keep = true;
    Check check;
    long added;
    /** Batches handed out by every source this try opened. */
    long batchesRead;
    long rowsRead;
    Path scratch;

    interface Check {
        void row(Map<String, Object> row);
    }

    StreamTry(Map<String, Object> config, Dataset input, long budgetBytes, Path dir, int batch) {
        this.config = config;
        this.input = input;
        this.memory = new RunMemory(budgetBytes);
        this.dir = dir;
        this.batch = batch;
    }

    private RowSource counted(RowSource source) {
        return new RowSource() {
            @Override public List<String> columns() { return source.columns(); }

            @Override public long size() { return source.size(); }

            @Override
            public List<Map<String, Object>> next() throws Exception {
                List<Map<String, Object>> next = source.next();
                if (next != null) {
                    batchesRead++;
                    rowsRead += next.size();
                }
                return next;
            }

            @Override public void close() throws Exception { source.close(); }
        };
    }

    @Override
    public RowSource openInput() {
        return this.counted(RowSource.of(this.input, this.batch));
    }

    @Override
    public RowSource openOutputOf(String stepKey) {
        Dataset output = this.outputs.get(stepKey);
        if (output == null) {
            throw new IllegalStateException("step <" + stepKey + "> has no output to read");
        }
        return this.counted(RowSource.of(output, this.batch));
    }

    @Override
    public RowSink output() {
        return new RowSink() {
            @Override
            public void declare(Collection<String> columns) {
                declared.clear();
                declared.addAll(columns);
            }

            @Override
            public void add(Map<String, Object> row) {
                added++;
                if (check != null) {
                    check.row(row);
                }
                if (keep) {
                    rows.add(row);
                }
            }

            @Override
            public long size() {
                return added;
            }
        };
    }

    @Override
    public Path scratch() throws IOException {
        if (this.scratch == null) {
            this.scratch = Files.createDirectories(this.dir.resolve("scratch"));
        }
        return this.scratch;
    }

    long scratchFiles() throws IOException {
        if (this.scratch == null || !Files.exists(this.scratch)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(this.scratch)) {
            return files.count();
        }
    }

    @Override public RunMemory memory() { return this.memory; }

    @Override public long maxFileBytes() { return Long.MAX_VALUE; }

    @Override public long maxRows() { return Long.MAX_VALUE; }

    @Override public KeptFile keepFile(String fileName, List<String> columns, long rows, FileWriter writer) { throw new UnsupportedOperationException(); }

    @Override public long tenantId() { return 1; }

    @Override public long jobQueueId() { return 2; }

    @Override public int attempt() { return 1; }

    @Override public String stepKey() { return "step"; }

    @Override public int tryNumber() { return 1; }

    @Override public Map<String, Object> config() { return this.config; }

    @Override public Dataset input() { return this.input; }

    @Override public Dataset dataset(String stepKey) { return this.outputs.get(stepKey); }

    @Override public void keepFile(String fileName, byte[] content, long rows, List<String> columns) { throw new UnsupportedOperationException(); }

    @Override public void recordOutput(RunOutput output) { }

    @Override public void log(String message) { this.lines.add("INFO " + message); }

    @Override public void warn(String message) { this.lines.add("WARN " + message); }
}
