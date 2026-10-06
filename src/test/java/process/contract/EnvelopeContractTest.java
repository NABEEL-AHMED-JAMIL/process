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
        internal.put(DefinitionException.class, "caught in PipelineDefinitionService: a definition that does not read or "
            + "validate is answered in words, every problem at its path (MIG-230)");
        internal.put(declared("process.pipeline.StepEngine$StepFailure"), "carries a step task's checked exception off its try "
            + "thread; the engine unwraps it into the step's error and never lets it out (MIG-230)");
        internal.put(declared("process.forms.PublicForms$Refused"), "caught in PublicFormRestApi: a share link's refusal answered "
            + "with its own status and sentence, nothing else (MIG-278)");
        internal.put(FormFields.Refused.class, "caught in FormService.save: a form definition refused in words (Wave 5 Forms lite)");
        internal.put(FormFields.Unanswered.class, "caught in FormSubmissionService.submit: answers refused in words, each at its "
            + "field (Wave 5 Forms lite)");
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
