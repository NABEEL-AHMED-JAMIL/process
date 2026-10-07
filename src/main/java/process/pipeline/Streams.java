package process.pipeline;

import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.io.FilterOutputStream;

/** Helpers for streaming steps (MIG-344). */
public final class Streams {

    private Streams() {
    }

    /**
     * A streaming task's in-memory path: its input is the context's dataset, its output is collected (held to
     * {@link Limits}, as every in-memory dataset), its kept files are buffered and handed to {@link StepContext#keepFile},
     * and its scratch directory is a temporary one.
     */
    public static StepResult inMemory(StreamingStepTask task, StepContext context) throws Exception {
        InMemory adapted = new InMemory(context);
        try {
            StepResult result = task.stream(adapted);
            return result.isStreamed() ? StepResult.of(adapted.sink.toDataset()) : result;
        } finally {
            adapted.removeScratch();
        }
    }

    /** Removes a directory and everything under it; nothing when it is not there. */
    public static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // the sweep takes what is left
                }
            });
        } catch (IOException ignored) {
            // the sweep takes what is left
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /** An output stream that counts its bytes and refuses to pass {@code max}. */
    public static final class Bounded extends FilterOutputStream {
        private final long max;
        private final String what;
        private long count;

        public Bounded(OutputStream out, long max, String what) {
            super(out);
            this.max = max;
            this.what = what;
        }

        @Override
        public void write(int b) throws IOException {
            this.grow(1);
            this.out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            this.grow(len);
            this.out.write(b, off, len);
        }

        private void grow(long n) {
            this.count += n;
            if (this.count > this.max) {
                throw new IllegalStateException(String.format("%s is more than %,d bytes; a step reads or writes at most %,d.", this.what,
                    this.max, this.max));
            }
        }

        public long count() {
            return this.count;
        }
    }

    private static final class InMemory implements StreamContext {
        private final StepContext context;
        final RowSink.Collecting sink = RowSink.collecting("The output");
        private final RunMemory memory = RunMemory.ofMegabytes(RunMemory.DEFAULT_MB);
        private Path scratch;

        InMemory(StepContext context) {
            this.context = context;
        }

        void removeScratch() {
            deleteTree(this.scratch);
        }

        @Override
        public RowSource openInput() {
            return RowSource.of(this.context.input());
        }

        @Override
        public RowSink output() {
            return this.sink;
        }

        @Override
        public Path scratch() throws IOException {
            if (this.scratch == null) {
                this.scratch = Files.createTempDirectory("step-scratch-");
            }
            return this.scratch;
        }

        @Override
        public RunMemory memory() {
            return this.memory;
        }

        @Override
        public long maxFileBytes() {
            return Limits.MAX_FILE_BYTES;
        }

        @Override
        public long maxRows() {
            return Limits.MAX_ROWS;
        }

        @Override
        public KeptFile keepFile(String fileName, List<String> columns, long rows, FileWriter writer) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MessageDigest digest = sha256();
            try (OutputStream out = new DigestOutputStream(bytes, digest)) {
                writer.write(out);
            }
            Limits.requireBytes(bytes.size(), "The file");
            this.context.keepFile(fileName, bytes.toByteArray(), rows, columns);
            return new KeptFile(bytes.size(), hex(digest.digest()));
        }

        // ---- the rest is the context's ----

        @Override public long tenantId() { return this.context.tenantId(); }

        @Override public long jobQueueId() { return this.context.jobQueueId(); }

        @Override public int attempt() { return this.context.attempt(); }

        @Override public String stepKey() { return this.context.stepKey(); }

        @Override public int tryNumber() { return this.context.tryNumber(); }

        @Override public Map<String, Object> config() { return this.context.config(); }

        @Override public Dataset input() { return this.context.input(); }

        @Override public long inputSize() { return this.context.inputSize(); }

        @Override public Map<String, Object> firstInputRow() throws Exception { return this.context.firstInputRow(); }

        @Override public Long jobId() { return this.context.jobId(); }

        @Override public String pipelineId() { return this.context.pipelineId(); }

        @Override public Long jobOwnerUserId() { return this.context.jobOwnerUserId(); }

        @Override public String inputBucket() { return this.context.inputBucket(); }

        @Override public String inputKey() { return this.context.inputKey(); }

        @Override public List<String> inputKeys() { return this.context.inputKeys(); }

        @Override public Dataset dataset(String stepKey) throws Exception { return this.context.dataset(stepKey); }

        @Override
        public void keepFile(String fileName, byte[] content, long rows, List<String> columns) throws Exception {
            this.context.keepFile(fileName, content, rows, columns);
        }

        @Override public void recordOutput(RunOutput output) throws Exception { this.context.recordOutput(output); }

        @Override public void log(String message) { this.context.log(message); }

        @Override public void warn(String message) { this.context.warn(message); }
    }
}
