package process.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.ai.AiStepService;
import process.ai.PromptRunner;
import process.model.dto.AiWorkerRunDto;
import process.model.dto.ResponseDto;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.AiPromptVersionRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotifyService;
import process.model.service.impl.AiModelConnectionServiceImpl;
import process.model.service.impl.AiPromptServiceImpl;
import process.security.RunCallbackTokens;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-145: the worker-callback 401 is a string, compared across layers.
 *
 * AiPromptRestApi.run decides its HTTP status by comparing the service's message against the
 * literal "Unauthorized worker callback." -- it has no other way to tell a refused token from an
 * ordinary failed step, both of which come back as an ERROR ResponseDto. The same words are written
 * independently in three more places: AiPromptServiceImpl.runForWorker (which the controller reads),
 * NotifyResetApi.rejectIfUntrusted and MeterRestApi.verifyRun (which the worker's client matches on).
 * Rewording any one of them turns a 401 into a 200 somewhere, silently. This drives all four real
 * code paths and asserts they agree, and that one character off is exactly that silent 200.
 */
class WorkerCallbackRefusalWordingTest {

    private static final String WORDING = "Unauthorized worker callback.";

    private final RunCallbackTokens tokens = mock(RunCallbackTokens.class);

    private static String messageOf(ResponseEntity<?> answer) {
        return ((ResponseDto) answer.getBody()).getMessage();
    }

    /** The service's refusal, from the real AiPromptServiceImpl with a token that does not verify. */
    private ResponseDto serviceRefusal() {
        when(this.tokens.verify(any(), any(), any())).thenReturn(Optional.of(RunCallbackTokens.Refusal.MISMATCH));
        AiPromptServiceImpl service = new AiPromptServiceImpl(mock(AiPromptRepository.class), mock(AiPromptVersionRepository.class),
            mock(AiPromptRunRepository.class), mock(TenantRepository.class), mock(PipelineRepository.class),
            mock(AiModelConnectionServiceImpl.class), mock(PromptRunner.class), mock(UserNameResolver.class),
            mock(AiStepService.class), this.tokens, mock(JobQueueRepository.class), mock(SourceJobRepository.class));
        AiWorkerRunDto dto = new AiWorkerRunDto();
        dto.setJobId(2600L); dto.setJobQueueId(6000L); dto.setStepTag("summary"); dto.setPromptUuid("p-uuid");
        return service.runForWorker(dto, "forged");
    }

    @Test
    void allFourPlacesSayTheSameWordsAndTheControllerTurnsThemIntoA401() throws Exception {
        ResponseDto fromService = this.serviceRefusal();

        when(this.tokens.verify(any(), any(), anyString())).thenReturn(Optional.of(RunCallbackTokens.Refusal.MISMATCH));
        ResponseEntity<?> fromNotify = new NotifyResetApi(mock(NotifyService.class), this.tokens)
            .rejectIfUntrusted(2600L, 6000L, "forged");

        when(this.tokens.verifyForReport(any(), any(), any())).thenReturn(Optional.of(RunCallbackTokens.Refusal.MISMATCH));
        MeterRestApi.VerifyRunDto verify = new MeterRestApi.VerifyRunDto();
        verify.jobId = 2600L; verify.jobQueueId = 6000L;
        ResponseEntity<?> fromMeter = new MeterRestApi(this.tokens, mock(JobQueueRepository.class), mock(SourceJobRepository.class))
            .verifyRun("forged", verify);

        AiPromptServiceImpl service = mock(AiPromptServiceImpl.class);
        when(service.runForWorker(any(), any())).thenReturn(fromService);
        ResponseEntity<?> fromController = new AiPromptRestApi(service).run("forged", new AiWorkerRunDto());

        assertThat(fromService.getMessage()).isEqualTo(WORDING);
        assertThat(messageOf(fromNotify)).isEqualTo(WORDING);
        assertThat(messageOf(fromMeter)).isEqualTo(WORDING);
        assertThat(fromNotify.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fromMeter.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The service's own words are what the controller compares against; they must produce the 401.
        assertThat(fromController.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(messageOf(fromController)).isEqualTo(WORDING);
    }

    /**
     * The hazard itself: the controller never looks at anything but the words. A refusal worded
     * one character differently -- no full stop -- is answered 200, not 401.
     */
    @Test
    void oneCharacterOffAndTheControllerAnswersTwoHundred() throws Exception {
        AiPromptServiceImpl service = mock(AiPromptServiceImpl.class);
        when(service.runForWorker(any(), any())).thenReturn(new ResponseDto("ERROR", "Unauthorized worker callback"));

        ResponseEntity<?> answer = new AiPromptRestApi(service).run("forged", new AiWorkerRunDto());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
