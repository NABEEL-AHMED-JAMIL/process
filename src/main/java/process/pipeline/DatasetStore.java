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

    /**
     * datasets/{run}/{attempt}/{step}/{name}.json -- feature plan 21.3's datasets/{execution_id}/{step}/{name}.json with
     * the attempt, so a retried run never reads the output of the attempt before it. The step key and name are
     * [a-z][a-z0-9_]* (DefinitionValidator), so the key is a plain path.
     */
    static String keyOf(long jobQueueId, int attempt, String stepKey, String name) {
        return String.format("datasets/%d/%d/%s/%s.json", jobQueueId, attempt, stepKey, name);
    }
}
