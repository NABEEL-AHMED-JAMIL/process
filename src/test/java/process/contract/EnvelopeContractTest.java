package process.contract;

import org.barco.platform.contract.EnvelopeContract;
import org.springframework.dao.DataIntegrityViolationException;
import process.ai.AiPort;
import process.config.GlobalExceptionHandler;
import process.filechat.FileIndexLock;
import process.media.UnreadableFileException;
import org.barco.platform.security.LoginAttemptGuard;
import process.security.TenantIsolationException;
import process.security.TokenRevocations;
import process.model.dto.ResponseDto;
import process.util.RequestRefused;

import java.util.Collections;
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
        internal.put(FileIndexLock.Busy.class, "caught in FileChatServiceImpl: 'still being prepared', no second extraction (MIG-111)");
        internal.put(FileIndexLock.Unavailable.class, "caught in FileChatServiceImpl: answered from the raw file, nothing indexed (MIG-111)");
        internal.put(UnreadableFileException.class, "caught in FileChatServiceImpl and answered in words");
        internal.put(declared("process.model.service.impl.FileChatServiceImpl$UnsupportedFileTypeException"), "caught in FileChatServiceImpl and answered in words");
        internal.put(LoginAttemptGuard.Unavailable.class, "caught in AuthServiceImpl: sign-in refused with one sentence before any lookup (MIG-109)");
        internal.put(TokenRevocations.Unavailable.class, "caught in LocalIdentity.authenticate and AuthServiceImpl: fails closed (MIG-14)");
        internal.put(TenantIsolationException.class, "an infrastructure fault -- the tenant filter could not be turned on -- so the "
            + "request fails closed with the fixed 500 sentence and nothing of the cause (MIG-11)");
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
