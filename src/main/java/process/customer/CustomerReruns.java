package process.customer;

import org.springframework.stereotype.Component;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.pipeline.review.CustomerRunAgain;
import process.security.TenantContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-334: the customer's rejection with rerun, the API's way. A run started through the API is started again with the
 * same intake -- the same record and files, under its own api_intake row -- by Run now's rules (RunIntake.again). A run
 * started any other way (the console, a schedule, an inbox arrival) is not the API's to start: the rejection stands and
 * the answer says why; the workspace runs it again in the console if it wants to.
 */
@Component
public class CustomerReruns implements CustomerRunAgain {

    static final String NOT_STARTED_BY_THE_API = "Only a run started through the API is run again through it; this one was started "
        + "in the console, on a schedule or by an arrival. The rejection is recorded.";

    private final CustomerRunStore runs;
    private final PipelineCatalogue catalogue;
    private final RunIntake intake;

    public CustomerReruns(CustomerRunStore runs, PipelineCatalogue catalogue, RunIntake intake) {
        this.runs = runs;
        this.catalogue = catalogue;
        this.intake = intake;
    }

    @Override
    public Map<String, Object> runAgain(JobQueue rejected, SourceJob job) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        long tenantId = rejected.getTenantId();
        Optional<CustomerRunStore.Intake> given = this.runs.intakeOf(tenantId, rejected.getJobQueueId());
        Optional<PipelineCatalogue.Entry> pipeline = this.catalogue.find(tenantId, String.valueOf(job.getJobId()));
        if (!given.isPresent() || !pipeline.isPresent()) {
            return notQueued(outcome, NOT_STARTED_BY_THE_API);
        }
        RunIntake.Outcome started = this.intake.again(pipeline.get(), given.get(), TenantContext.getClientId(),
            "a re-run after the customer rejected run " + rejected.getJobQueueId());
        if (started.refusal != null) {
            return notQueued(outcome, started.refusal.getDetail());
        }
        outcome.put("queued", true);
        outcome.put("jobQueueId", Long.valueOf(String.valueOf(started.run.get("id"))));
        outcome.put("message", "Run " + started.run.get("id") + " runs the pipeline again with the same intake.");
        return outcome;
    }

    private static Map<String, Object> notQueued(Map<String, Object> outcome, String why) {
        outcome.put("queued", false);
        outcome.put("jobQueueId", null);
        outcome.put("message", why);
        return outcome;
    }
}
