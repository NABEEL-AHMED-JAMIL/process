package process.pipeline.tasks;

import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A step's context for a task's unit test: its config, its input, other steps' outputs, and what it logged and kept. */
final class TaskContext implements StepContext {

    static final long TENANT = 4101L;
    static final long RUN = 88001L;

    final Map<String, Object> config;
    final Dataset input;
    final Map<String, Dataset> outputs = new LinkedHashMap<>();
    final List<String> lines = new ArrayList<>();
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final List<RunOutput> recorded = new ArrayList<>();
    Long owner = 7L;
    String inputBucket;
    String inputKey;

    TaskContext(Map<String, Object> config, Dataset input) {
        this.config = config;
        this.input = input;
    }

    static TaskContext of(Map<String, Object> config, List<Map<String, Object>> rows) {
        return new TaskContext(config, Dataset.of(rows));
    }

    @Override
    public long tenantId() {
        return TENANT;
    }

    @Override
    public long jobQueueId() {
        return RUN;
    }

    @Override
    public int attempt() {
        return 1;
    }

    @Override
    public String stepKey() {
        return "step";
    }

    @Override
    public int tryNumber() {
        return 1;
    }

    @Override
    public Map<String, Object> config() {
        return this.config;
    }

    @Override
    public Dataset input() {
        return this.input;
    }

    @Override
    public Long jobId() {
        return 501L;
    }

    @Override
    public String pipelineId() {
        return "CLAIMS";
    }

    @Override
    public Long jobOwnerUserId() {
        return this.owner;
    }

    @Override
    public String inputBucket() {
        return this.inputBucket;
    }

    @Override
    public String inputKey() {
        return this.inputKey;
    }

    @Override
    public Dataset dataset(String stepKey) {
        Dataset dataset = this.outputs.get(stepKey);
        if (dataset == null) {
            throw new IllegalStateException("no output " + stepKey);
        }
        return dataset;
    }

    @Override
    public void keepFile(String fileName, byte[] content, long rows, List<String> columns) {
        this.files.put(fileName, content);
    }

    @Override
    public void recordOutput(RunOutput output) {
        this.recorded.add(output);
    }

    @Override
    public void log(String message) {
        this.lines.add("INFO " + message);
    }

    @Override
    public void warn(String message) {
        this.lines.add("WARN " + message);
    }
}
