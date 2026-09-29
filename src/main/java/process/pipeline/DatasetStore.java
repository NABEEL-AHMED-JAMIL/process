package process.pipeline;

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
}
