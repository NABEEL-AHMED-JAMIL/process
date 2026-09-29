package process.pipeline;

import process.ai.AiPort;
import process.pipeline.backing.ReportRenderer;

import process.pipeline.backing.Fakes;
import process.pipeline.tasks.AggregateStepTask;
import process.pipeline.tasks.AiPromptStepTask;
import process.pipeline.tasks.ComputeStepTask;
import process.pipeline.tasks.RenderPdfStepTask;
import process.pipeline.tasks.EnrichStepTask;
import process.pipeline.tasks.FilterStepTask;
import process.pipeline.tasks.JoinStepTask;
import process.pipeline.tasks.LegacyStepTask;
import process.pipeline.tasks.MeasureImageStepTask;
import process.pipeline.tasks.ReadApiStepTask;
import process.pipeline.tasks.ReadDatabaseStepTask;
import process.pipeline.tasks.ReadFileStepTask;
import process.pipeline.tasks.ReadS3StepTask;
import process.pipeline.tasks.SampleStepTask;
import process.pipeline.tasks.SaveFileStepTask;
import process.pipeline.tasks.SelectStepTask;
import process.pipeline.tasks.SendNotificationStepTask;
import process.pipeline.tasks.TransformStepTask;
import process.pipeline.tasks.UploadBucketStepTask;
import process.pipeline.tasks.ValidateStepTask;
import process.pipeline.tasks.WriteDatabaseStepTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.mock;

/** Every task the application registers (MIG-231), on fakes of the services they call. */
public final class AllTasks {

    public final Fakes.Api api = new Fakes.Api();
    public final Fakes.Contracts contracts = new Fakes.Contracts();
    public final Fakes.Database database = new Fakes.Database();
    public final Fakes.Buckets buckets = new Fakes.Buckets();
    public final Fakes.Notifier notifier = new Fakes.Notifier();
    public final AiPort ai = mock(AiPort.class);
    public final ReportRenderer reports = mock(ReportRenderer.class);

    public List<StepTask> list() {
        return new ArrayList<>(Arrays.asList(new LegacyStepTask(), new SampleStepTask(), new SelectStepTask(),
            new ReadApiStepTask(this.api), new ReadS3StepTask(this.buckets), new ReadFileStepTask(this.buckets),
            new ReadDatabaseStepTask(this.database), new ValidateStepTask(this.contracts), new TransformStepTask(), new FilterStepTask(),
            new JoinStepTask(), new EnrichStepTask(this.api), new AggregateStepTask(), new SaveFileStepTask(),
            new UploadBucketStepTask(this.buckets), new WriteDatabaseStepTask(this.database), new SendNotificationStepTask(this.notifier),
            new AiPromptStepTask(this.ai, this.buckets), new ComputeStepTask(), new MeasureImageStepTask(this.buckets),
            new RenderPdfStepTask(this.reports)));
    }

    public StepTasks tasks() {
        return new StepTasks(this.list());
    }
}
