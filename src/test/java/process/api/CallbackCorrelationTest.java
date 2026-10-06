package process.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.service.NotifyService;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-95: a worker callback is logged under the correlation id of the dispatch that caused it.
 *
 * Two paths, both covered for /changeState, /addLogs and /addLogsBatch. A cooperating worker echoes
 * X-Correlation-Id, which CorrelationIdFilter has already bound by the time the controller runs, and it
 * is kept. A legacy worker echoes nothing, so the filter bound a fresh id with no thread back to the
 * dispatch -- the controller replaces it with the one stamped on job_queue at dispatch. The id is never
 * an authentication input, and a caller whose token is not the run's own is never told the run's id.
 */
@ExtendWith(MockitoExtension.class)
class CallbackCorrelationTest {

    private static final String TOKEN = "cbt_1.5705.some-random-part";
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;
    private static final String DISPATCHED_UNDER = "corr-dispatch-5705";
    private static final String FRESH_FROM_FILTER = "corr-fresh-from-filter";

    @Mock private NotifyService notifyService;
    @Mock private RunCallbackTokens runCallbackTokens;

    private NotifyResetApi api;
    private final List<String> boundDuringTheCall = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.api = new NotifyResetApi(this.notifyService, this.runCallbackTokens);
        lenient().when(this.runCallbackTokens.correlationOf(QUEUE_ID))
            .thenReturn(Optional.of(new RunCallbackTokens.RunCorrelation(DISPATCHED_UNDER, 1001L)));
        ResponseDto ok = new ResponseDto(ProcessUtil.SUCCESS, "ok");
        lenient().when(this.notifyService.changeState(any(SourceJobQueueDto.class), any())).thenAnswer(inv -> this.bound(ok));
        lenient().when(this.notifyService.addLogs(any(SourceJobQueueDto.class), any())).thenAnswer(inv -> this.bound(ok));
        lenient().when(this.notifyService.addLogsBatch(anyLong(), anyLong(), anyList(), any())).thenAnswer(inv -> this.bound(ok));
    }

    @AfterEach
    void clear() {
        CorrelationId.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private ResponseDto bound(ResponseDto answer) {
        this.boundDuringTheCall.add(CorrelationId.current());
        return answer;
    }

    private static SourceJobQueueDto line() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobStatusMessage("read 4,200 rows");
        return dto;
    }

    private void everyEndpoint(String echoed) {
        // What CorrelationIdFilter does before the controller: the echoed id, or a fresh one.
        CorrelationId.set(echoed != null ? echoed : FRESH_FROM_FILTER);
        this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Running, TOKEN, null, echoed, line());
        CorrelationId.set(echoed != null ? echoed : FRESH_FROM_FILTER);
        this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, null, echoed, line());
        CorrelationId.set(echoed != null ? echoed : FRESH_FROM_FILTER);
        this.api.addLogsBatch(JOB_ID, QUEUE_ID, TOKEN, null, echoed,
            Collections.singletonMap("messages", Arrays.asList("opened source", "read 4,200 rows")));
    }

    @Test
    void aLegacyWorkerThatEchoesNothingIsLoggedUnderItsDispatchsId() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());

        this.everyEndpoint(null);

        assertThat(this.boundDuringTheCall).containsExactly(DISPATCHED_UNDER, DISPATCHED_UNDER, DISPATCHED_UNDER);
    }

    /**
     * MIG-94: behind the gateway no callback arrives without an id -- the gateway mints one for a worker that sends
     * none (job-search's Python workers) -- so "keep what arrived" meant MIG-95's fallback never ran live: every
     * callback of run 7318 was logged under a fresh id of its own (seen 2026-09-24). A verified callback is about
     * its run, so the run's own id is the one it is logged under, whatever arrived; a cooperating worker (the new
     * runtime) sends that same id anyway.
     */
    @Test
    void aVerifiedCallbackIsLoggedUnderItsRunsIdWhateverArrived() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());

        this.everyEndpoint("corr-minted-by-gateway");

        assertThat(this.boundDuringTheCall).containsExactly(DISPATCHED_UNDER, DISPATCHED_UNDER, DISPATCHED_UNDER);
    }

    /** The id it arrived under is named on the callback's line, so the gateway's access line still leads here. */
    @Test
    void theIdTheCallbackArrivedUnderIsNamedOnItsLine() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(NotifyResetApi.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            CorrelationId.set("corr-minted-by-gateway");
            this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, null, "corr-minted-by-gateway", line());
        } finally {
            logger.detachAppender(lines);
        }

        assertThat(lines.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("arrived as corr-minted-by-gateway");
            assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, DISPATCHED_UNDER);
        });
    }

    /** The answer carries the id the callback was logged under, as the filter's own answer would have. */
    @Test
    void theAnswerCarriesTheResolvedId() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(CorrelationId.HEADER, FRESH_FROM_FILTER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
        CorrelationId.set(FRESH_FROM_FILTER);

        this.api.addLogs(JOB_ID, QUEUE_ID, TOKEN, null, null, line());

        assertThat(response.getHeaders(CorrelationId.HEADER)).containsExactly(DISPATCHED_UNDER);
    }

    /** Correlation is not authentication: a wrong token is refused, and is not told the run's id. */
    @Test
    void aCallerWhoseTokenIsNotTheRunsOwnIsNeverToldTheRunsId() {
        for (RunCallbackTokens.Refusal why : new RunCallbackTokens.Refusal[] {RunCallbackTokens.Refusal.MISMATCH,
                RunCallbackTokens.Refusal.NOT_ISSUED, RunCallbackTokens.Refusal.WRONG_JOB, RunCallbackTokens.Refusal.NO_SUCH_RUN}) {
            when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, "x")).thenReturn(Optional.of(why));
            CorrelationId.set(FRESH_FROM_FILTER);
            this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Running, "x", null, null, line());
            assertThat(CorrelationId.current()).as(why.name()).isEqualTo(FRESH_FROM_FILTER);
        }
        verify(this.runCallbackTokens, never()).correlationOf(any());
    }

    /**
     * MIG-18's distinction: one operation, many callbacks. Every heartbeat of a run carries the same
     * correlation id, and every one is applied -- the correlation id is never used as the idempotency key.
     */
    @Test
    void theCorrelationIdIsNotAnIdempotencyKey() {
        when(this.runCallbackTokens.verify(JOB_ID, QUEUE_ID, TOKEN)).thenReturn(Optional.empty());

        for (int beat = 0; beat < 3; beat++) {
            CorrelationId.set(DISPATCHED_UNDER);
            this.api.changeState(JOB_ID, QUEUE_ID, JobStatus.Running, TOKEN, null, DISPATCHED_UNDER, line());
        }

        verify(this.notifyService, times(3)).changeState(any(SourceJobQueueDto.class), isNull());
        verify(this.notifyService, never()).changeState(any(SourceJobQueueDto.class), eq(DISPATCHED_UNDER));
    }
}
