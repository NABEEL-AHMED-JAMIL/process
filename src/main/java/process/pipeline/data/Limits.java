package process.pipeline.data;

import process.pipeline.Dataset;

/**
 * How big a dataset a step may hold (MIG-231). A run's datasets are held whole in memory by the step that reads them
 * (and written to local disk between steps), on up to process.pipeline.engine.threads runs at once, so every task that
 * makes rows stops at these instead of growing without bound:
 *
 * <ul>
 *   <li>{@value #MAX_ROWS} rows, {@value #MAX_COLUMNS} columns, and {@value #MAX_CELLS} cells (rows x columns) --
 *       about 100-200 MB of heap for the widest dataset;</li>
 *   <li>{@value #MAX_FILE_BYTES} bytes for a file read from or written to a bucket, or kept by Save File;</li>
 *   <li>{@value #MAX_CALLS} API calls per Enrich step; {@value #MAX_OBJECTS} objects per Read S3 step.</li>
 * </ul>
 *
 * Exceeding one fails the step, never truncates silently: a read that stops early says so only when the step asked
 * for fewer rows ({@code maxRows}).
 */
public final class Limits {

    public static final int MAX_ROWS = 50_000;
    public static final int MAX_COLUMNS = 200;
    public static final long MAX_CELLS = 1_000_000L;
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    public static final int MAX_CALLS = 500;
    public static final int MAX_OBJECTS = 1_000;

    private Limits() {
    }

    /** The dataset, or an exception saying which bound it passed. */
    public static Dataset require(Dataset dataset, String what) {
        requireShape(dataset.size(), dataset.getColumns().size(), what);
        return dataset;
    }

    public static void requireShape(long rows, int columns, String what) {
        if (rows > MAX_ROWS) {
            throw new IllegalStateException(String.format("%s has more than %,d rows, the most a step holds.", what, MAX_ROWS));
        }
        if (columns > MAX_COLUMNS) {
            throw new IllegalStateException(String.format("%s has %d columns; a step holds at most %d.", what, columns, MAX_COLUMNS));
        }
        if (rows * columns > MAX_CELLS) {
            throw new IllegalStateException(String.format("%s has %,d cells (%,d rows x %d columns); a step holds at most %,d.", what,
                rows * columns, rows, columns, MAX_CELLS));
        }
    }

    public static void requireBytes(long bytes, String what) {
        if (bytes > MAX_FILE_BYTES) {
            throw new IllegalStateException(String.format("%s is %,d bytes; a step reads or writes at most %,d.", what, bytes, MAX_FILE_BYTES));
        }
    }
}
