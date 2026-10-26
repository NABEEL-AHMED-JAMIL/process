package process.contract;

import process.forms.FormFields;
import process.pipeline.DefinitionException;
import process.config.KafkaRouteUnresolvedException;
import org.barco.platform.contract.EnvelopeContract;
import org.springframework.dao.DataIntegrityViolationException;
import process.ai.AiPort;
import process.config.GlobalExceptionHandler;
import process.filechat.FileIndexLock;
import process.identity.IdentityPort;
import process.media.UnreadableFileException;
import process.security.TenantIsolationException;
import process.security.TokenRevocations;
import process.model.dto.ResponseDto;
import process.util.RequestRefused;

import java.util.Collections;
import process.customer.InputContracts;
import process.customer.CustomerFileReads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIG-103 (ADR-023): process answers by the platform's envelope contract, like every extracted
 * service -- a refusal is a 200 carrying ERROR and its sentence; access denied 403; a wrong-typed
 * value or an unreadable body 400; anything else the fixed 500. The console keys on body.status.
 */
class EnvelopeContractTest extends EnvelopeContract {

    @Override
    protected Object[] advice() {
        return new Object[] { new GlobalExceptionHandler() };
    }

    @Override
    protected Class<?> envelope() {
        return ResponseDto.class;
    }

    @Override
    protected String servicePackage() {
        return "process";
    }

    @Override
    protected List<Exception> refusals() {
        return Collections.singletonList(new RequestRefused("Invalid date -- expected yyyy-MM-dd."));
    }

    /** MIG-71: a write that lost a race to one of Core's unique rules is a 409 in words -- RaceRefusals' own tests pin it. */
    @Override
    protected Map<Class<? extends Throwable>, String> pinnedElsewhere() {
        return Collections.singletonMap(DataIntegrityViolationException.class,
            "409 CONFLICT with the rule's sentence when RaceRefusals recognises the constraint, else the fixed 500; "
                + "pinned by RaceRefusalsTest and the MIG-71 Postgres tests");
    }

    /** Declared by process, never at the edge: each is caught where it happens and turned into words. */
    @Override
    protected Map<Class<? extends Throwable>, String> deliberatelyInternal() {
        Map<Class<? extends Throwable>, String> internal = new LinkedHashMap<>();
        internal.put(AiPort.AiUnavailableException.class, "caught by AiStepService (a failed step, by the step's on-error rule) "
            + "and PipelineServiceImpl (a form save refused in words) -- ADR-020");
        internal.put(InputContracts.Unavailable.class, "MIG-332: thrown by HttpIntegrationPipelines.check and caught in"
            + " CustomerPipelines, CustomerEvents and EventRoutes -- a 503 problem or a refusal in words; never reaches the console's edge");
        internal.put(CustomerFileReads.Unavailable.class, "MIG-334: thrown by CustomerFileReads.resolve when storage-service's"
            + " directory cannot answer and caught in CustomerFileReads itself -- a 503 problem; never reaches the console's edge");
        internal.put(FileIndexLock.Busy.class, "caught in FileChatServiceImpl: 'still being prepared', no second extraction (MIG-111)");
        internal.put(FileIndexLock.Unavailable.class, "caught in FileChatServiceImpl: answered from the raw file, nothing indexed (MIG-111)");
        internal.put(UnreadableFileException.class, "caught in FileChatServiceImpl and answered in words");
        internal.put(declared("process.model.service.impl.FileChatServiceImpl$UnsupportedFileTypeException"), "caught in FileChatServiceImpl and answered in words");
        internal.put(TokenRevocations.Unavailable.class, "caught in LocalIdentity.authenticate: fails closed (MIG-14)");
        internal.put(IdentityPort.Unavailable.class, "identity.mode=remote and Identity cannot be asked: a request that needs "
            + "a person or a workspace answers the fixed 500; the outbox and the name resolver catch it and carry on (MIG-107)");
        internal.put(TenantIsolationException.class, "an infrastructure fault -- the tenant filter could not be turned on -- so the "
            + "request fails closed with the fixed 500 sentence and nothing of the cause (MIG-11)");
        internal.put(KafkaRouteUnresolvedException.class, "caught where require() is called: DispatchRelay fails the run "
            + "with its sentence, the internal publish answers 422 with it, the topic test answers it in words (MIG-45)");
        internal.put(declared("process.pipeline.backing.MediaFiles$Refused"), "thrown by HttpMediaFiles to transcribe_audio and "
            + "convert_file only, inside a step's try: a busy answer is asked again, any other is the row's failure (onError) or "
            + "the step's error in media's words; no request thread meets it");
        internal.put(declared("process.pipeline.backing.ReportStudio$Busy"), "MIG-418: thrown by HttpReportStudio inside a "
            + "Generate report step only (media answered 429/503): asked again under Calls at once, else the step's error in media's "
            + "words; no request thread meets it");
        internal.put(declared("process.pipeline.PipelineCalls$Refused"), "MIG-424: thrown by PipelineCalls inside a Run pipeline "
            + "step's try only (another workspace's pipeline, a loop, too deep, parameters that do not stand): the row's outcome or the "
            + "step's error in words; no request thread meets it");
        internal.put(declared("process.pipeline.tasks.AtOnce$Busy"), "MIG-418: a per-row step's busy call, caught in AtOnce and "
            + "asked again (or the row's failure); never leaves a step");
        internal.put(declared("process.pipeline.tasks.CallApiStepTask$Stop"), "MIG-418: carries a call that ends the step out of "
            + "AtOnce to CallApiStepTask's own loop, which answers it as the step's error; never leaves the step");
        internal.put(DefinitionException.class, "caught in PipelineDefinitionService: a definition that does not read or "
            + "validate is answered in words, every problem at its path (MIG-230)");
        internal.put(declared("process.pipeline.StepEngine$StepFailure"), "carries a step task's checked exception off its try "
            + "thread; the engine unwraps it into the step's error and never lets it out (MIG-230)");
        internal.put(declared("process.forms.PublicForms$Refused"), "caught in PublicFormRestApi: a share link's refusal answered "
            + "with its own status and sentence, nothing else (MIG-278)");
        internal.put(declared("process.pipeline.review.RunReviewBulk$Refused"), "caught in RunReviewBulkRestApi (bulkDecide and the "
            + "review policy): a bulk decision or a policy read or save refused as a whole, answered with its own status (400 or 429) "
            + "and sentence in the envelope (bulk review)");
        internal.put(declared("process.monitoring.AlertRules$Refused"), "caught in AlertRestApi and FailedRunsRestApi (MIG-355): an alert "
            + "rule, an alert, a failed-runs search or a bulk retry refused as a whole, answered with its own status (400, 404 or 429) and "
            + "sentence in the envelope");
        internal.put(declared("process.backfill.Backfills$Refused"), "caught in BackfillRestApi and CustomerBackfills (MIG-347): a "
            + "backfill's preview, start, pause, resume, cancel, retry or read refused as a whole, answered with its own status (400, 404, "
            + "409 or 422) and sentence -- in the envelope for the console, as problem+json for the customer API");
        internal.put(declared("process.billing.BillingPort$BillingUnavailable"), "caught in Backfills (MIG-347: a backfill's cost "
            + "estimate and billed amount are said to be unavailable) and WorkspaceZones (the business zone until billing answers); "
            + "never reaches the console's edge");
        internal.put(declared("process.retention.RetentionService$Refused"), "caught in RetentionRestApi (MIG-354): a retention policy, "
            + "hold, confirmation or pass refused as a whole, answered with its own status (400, 404, 409, 429 or 502) and sentence in "
            + "the envelope");
        internal.put(declared("process.retention.RetentionTarget$Unavailable"), "caught in RetentionPass (MIG-354): a class whose service "
            + "offers no retention yet is recorded UNAVAILABLE in the pass; never reaches a controller");
        internal.put(declared("process.retention.RetentionPass$TooManyHeld"), "caught in RetentionPass (MIG-354): a class the holds keep "
            + "whole this pass is recorded HELD; never reaches a controller");
        internal.put(FormFields.Refused.class, "caught in FormService.save: a form definition refused in words (Wave 5 Forms lite)");
        internal.put(FormFields.Unanswered.class, "caught in FormSubmissionService.submit: answers refused in words, each at its "
            + "field (Wave 5 Forms lite)");
        internal.put(declared("process.pipeline.items.RunItems$Gone"), "MIG-420: thrown by RunItems.open for an item's file that is "
            + "no longer kept; caught in RunItems -- the file answers 410 in the envelope (logged), the zip leaves it out");
        return internal;
    }

    /** A private nested exception, named as the scan names it. */
    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable> declared(String name) {
        try {
            return (Class<? extends Throwable>) Class.forName(name);
        } catch (ClassNotFoundException gone) {
            throw new IllegalStateException(name + " no longer exists; drop its row", gone);
        }
    }
}
