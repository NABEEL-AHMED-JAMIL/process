package process.pipeline.tasks;

import process.pipeline.backing.DocumentDatasets;
import process.pipeline.backing.DocumentReadMarks;
import process.pipeline.data.RunMemory;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** MIG-364: a read_documents step run as a streaming try, for tests outside this package (the watermark's Postgres test). */
public final class ReadDocumentsSteps {

    private final ReadDocumentsStepTask task;

    public ReadDocumentsSteps(DocumentDatasets documents, DocumentReadMarks marks) {
        this.task = new ReadDocumentsStepTask(documents, marks);
    }

    /** Runs the step (key "step", type 1007, since the last run) as run {@code runId} of job {@code jobId}; answers its invoice numbers. */
    public List<String> sinceLastRun(long tenant, long jobId, long runId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("documentTypeId", 1007L);
        config.put("since", "last_run");
        try {
            StreamTry t = new StreamTry(config, null, RunMemory.DEFAULT_MB * 1024L * 1024L, Files.createTempDirectory("read-documents"), 1024);
            t.tenant = tenant;
            t.jobId = jobId;
            t.runId = runId;
            this.task.stream(t);
            return t.rows.stream().map(r -> String.valueOf(r.get("invoice_number"))).collect(Collectors.toList());
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
