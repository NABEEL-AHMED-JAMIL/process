package process.pipeline;

import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Map;

/**
 * Where a run's datasets live between its steps (MIG-230). A step's output is written here and recorded as a
 * run_dataset row -- its key, rows and columns -- and the next step is handed the reference, not the rows through the
 * database. Keys are built by the engine ({@link #keyOf}), never taken from a request.
 */
public interface DatasetStore {

    /** Writes a dataset under its key. */
    void write(String storageKey, Dataset dataset) throws Exception;

    /** The dataset under this key; an exception when there is none. */
    Dataset read(String storageKey) throws Exception;

    /** Removes one dataset; nothing when it is not there. */
    void delete(String storageKey) throws Exception;

    /** Writes a file a step made (Save File) under its key. */
    default void writeFile(String storageKey, byte[] content) throws Exception {
        throw new UnsupportedOperationException("This dataset store keeps no files.");
    }

    /** What a crash left behind (MIG-214): stale partial files and empty folders. Returns the partial files removed. */
    default int sweepLeftovers(Duration olderThan) throws Exception {
        return 0;
    }

    /** The bytes of a file a step made; an exception when there is none. */
    default byte[] readFile(String storageKey) throws Exception {
        throw new UnsupportedOperationException("This dataset store keeps no files.");
    }

    /**
     * datasets/{run}/{attempt}/{step}/files/{fileName}: a file a step made (MIG-231), beside the step's datasets and
     * swept with them. The file name is a plain name (the task's schema holds it to letters, digits, '.', '-', '_').
     */
    static String fileKeyOf(long jobQueueId, int attempt, String stepKey, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || fileName.contains("..")) {
            throw new IllegalArgumentException("Not a plain file name: " + fileName);
        }
        return String.format("datasets/%d/%d/%s/files/%s", jobQueueId, attempt, stepKey, fileName);
    }

    /**
     * datasets/{run}/{attempt}/{step}/{name}.json -- feature plan 21.3's datasets/{execution_id}/{step}/{name}.json with
     * the attempt, so a retried run never reads the output of the attempt before it. The step key and name are
     * [a-z][a-z0-9_]* (DefinitionValidator), so the key is a plain path.
     */
    static String keyOf(long jobQueueId, int attempt, String stepKey, String name) {
        return String.format("datasets/%d/%d/%s/%s.json", jobQueueId, attempt, stepKey, name);
    }

    // ---- MIG-344: datasets as streams ----------------------------------------------------------------------------

    /**
     * datasets/{run}/{attempt}/{step}/{name}.rows: a dataset in Core's row file format ({@code RowsFile}), written and
     * read a row at a time. Beside the ".json" keys of runs before it, which stay readable.
     */
    static String rowsKeyOf(long jobQueueId, int attempt, String stepKey, String name) {
        return String.format("datasets/%d/%d/%s/%s.rows", jobQueueId, attempt, stepKey, name);
    }

    /** The key a step's output is written under in this store: {@link #keyOf} unless the store keeps row files. */
    default String outputKeyOf(long jobQueueId, int attempt, String stepKey, String name) {
        return keyOf(jobQueueId, attempt, stepKey, name);
    }

    /** A dataset being written a row at a time: nothing is under its key until {@link #commit}. */
    interface Output extends RowSink {
        /** The columns it will have. */
        List<String> columns();

        /** Puts it under its key; returns its size in bytes (0 when the store does not say). */
        long commit() throws Exception;

        /** Drops it; nothing is left. Does nothing after a commit. */
        void abort();
    }

    /** Starts writing a dataset under {@code storageKey}. The default collects it and {@link #write}s it on commit. */
    default Output create(String storageKey) throws Exception {
        DatasetStore store = this;
        RowSink.Collecting rows = RowSink.collecting("The dataset");
        return new Output() {
            private boolean done;

            @Override
            public void declare(Collection<String> columns) {
                rows.declare(columns);
            }

            @Override
            public void add(Map<String, Object> row) {
                rows.add(row);
            }

            @Override
            public long size() {
                return rows.size();
            }

            @Override
            public List<String> columns() {
                return rows.toDataset().getColumns();
            }

            @Override
            public long commit() throws Exception {
                this.done = true;
                store.write(storageKey, rows.toDataset());
                return 0;
            }

            @Override
            public void abort() {
                this.done = true;
            }
        };
    }

    /** The dataset under this key, a batch at a time. The default reads it whole first. */
    default RowSource open(String storageKey, int batch) throws Exception {
        return RowSource.of(this.read(storageKey), batch);
    }

    /** A file being written under {@code storageKey}: nothing is there until {@link #commit}. */
    interface FileOutput {
        OutputStream stream();

        void commit() throws Exception;

        void abort();
    }

    /** Starts writing a file a step makes. The default buffers it and {@link #writeFile}s it on commit. */
    default FileOutput createFile(String storageKey) {
        DatasetStore store = this;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return new FileOutput() {
            @Override
            public OutputStream stream() {
                return bytes;
            }

            @Override
            public void commit() throws Exception {
                store.writeFile(storageKey, bytes.toByteArray());
            }

            @Override
            public void abort() {
            }
        };
    }

    /** A file's bytes as a stream (the caller closes it). The default reads it whole. */
    default InputStream openFile(String storageKey) throws Exception {
        return new ByteArrayInputStream(this.readFile(storageKey));
    }

    /** A file's size in bytes. */
    default long fileSize(String storageKey) throws Exception {
        return this.readFile(storageKey).length;
    }

    /**
     * A directory of one try's own for files it spills (MIG-344), under the run's: removed after the try, and with the
     * run's ({@link #removeScratch}) when the run ends. The default is a temporary directory.
     */
    default Path scratch(long jobQueueId, int attempt, String stepKey) throws IOException {
        return Files.createTempDirectory("step-scratch-");
    }

    /** Removes what a run's tries spilled and did not remove (a try that timed out and is still unwinding). */
    default void removeScratch(long jobQueueId, int attempt) {
    }

    /** The columns of a dataset, without reading its rows when the store can. */
    default List<String> columnsOf(String storageKey) throws Exception {
        try (RowSource source = this.open(storageKey, 1)) {
            return new ArrayList<>(source.columns());
        }
    }
}
