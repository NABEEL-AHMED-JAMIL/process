package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.FileFormats;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

/**
 * Save File (MIG-231): the step's input as a file -- CSV, JSON or JSON Lines -- kept with the run beside its datasets
 * (a run_dataset named by the file) for as long as the run's datasets are kept (settings.datasetRetentionHours). The
 * rows pass on unchanged: the next step reads what this one read. Pure Core; Parquet is read, not written, here.
 *
 * MIG-344: streamed -- the rows go straight from the input dataset to the file, its sha256 worked out on the way.
 */
@Component
public class SaveFileStepTask extends RegisteredTask implements StreamingStepTask {

    static final String FILE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$";

    static final TaskSpec SPEC = TaskSpec.builder("save_file", "Save File", TaskKind.OUTPUT)
        .description("Keeps the rows as a CSV, JSON or JSON Lines file with the run.")
        .input(TaskSpec.rows("The rows to save."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(JsonSchema.object()
            .required("fileName", JsonSchema.string().pattern(FILE_NAME).title("File name")
                .description("letters, digits, '.', '-' and '_', at most 128"))
            .property("format", JsonSchema.string().enumOf("csv", "json", "jsonl").title("Format").defaultValue("csv")))
        .aiToolName("save_file")
        .build();

    public SaveFileStepTask() {
        super(SPEC);
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        String fileName = Configs.text(context.config(), "fileName", null);
        String format = Configs.text(context.config(), "format", "csv");
        try (RowSource input = context.openInput()) {
            long rows = input.size();
            StreamContext.KeptFile kept = context.keepFile(fileName, input.columns(), rows, out -> FileFormats.writeTo(input, format, out));
            context.recordOutput(RunOutput.file(fileName, format, rows, kept.bytes).withSha256(kept.sha256));
            context.log(String.format("Saved %d row(s) as %s (%s, %,d bytes).", rows, fileName, format, kept.bytes));
            return StepResult.nothing(rows);
        }
    }
}
