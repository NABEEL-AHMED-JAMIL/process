package process.pipeline;

import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import process.pipeline.data.Limits;

/** What a streaming task is handed for one try (MIG-344), beside everything a {@link StepContext} gives. */
public interface StreamContext extends StepContext {

    /** The step's input from its first row: a batch at a time. Each call opens it again (an aggregate that spills reads it twice). */
    RowSource openInput() throws Exception;

    /** The step's output: rows added here are the step's output dataset when it returns {@link StepResult#streamed}. */
    RowSink output();

    /** A directory of this try's own, for files it spills; removed when the try ends, however it ends. */
    Path scratch() throws IOException;

    /** The run's memory budget: ask it before holding more than a batch. */
    RunMemory memory();

    /** The most bytes a file this step reads or writes may have (a read, a kept file, an upload). */
    long maxFileBytes();

    /**
     * The most rows this step may make: {@code Limits.MAX_ROWS} on the in-memory path (where every task keeps today's
     * bounds and messages), {@code Limits.MAX_STREAM_ROWS} when the engine streams.
     */
    long maxRows();

    /** Whether this try runs in memory (a dataset in, a dataset out) rather than streamed by the engine. */
    default boolean inMemory() {
        return this.maxRows() <= Limits.MAX_ROWS;
    }

    /** Writes a file's bytes; returns nothing (the caller counts its rows). */
    interface FileWriter {
        void write(OutputStream out) throws Exception;
    }

    /** A file kept with {@link #keepFile(String, List, long, FileWriter)}: its size and sha256. */
    final class KeptFile {
        public final long bytes;
        public final String sha256;

        public KeptFile(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    /**
     * Keeps a file this step writes, streamed straight to the run's file area (Save File): as {@link #keepFile(String,
     * byte[], long, List)} but never held whole. Returns its size and sha256 for the run's manifest.
     */
    KeptFile keepFile(String fileName, List<String> columns, long rows, FileWriter writer) throws Exception;
}
