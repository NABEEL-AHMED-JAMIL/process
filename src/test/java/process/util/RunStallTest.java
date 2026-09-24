package process.util;

import process.util.BusinessTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import process.model.dto.SourceJobDto;
import process.model.enums.JobStatus;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-63: the thirty-minute stall rule lives on the server, the rule scheduler1/next's stalled.ts drew
 * on its own, carried across unchanged: in flight (Queue, Start or Running), and lastJobRun more than
 * thirty minutes ago by the application clock -- strictly more, so exactly thirty is not yet stalled;
 * no lastJobRun, or one in the future (a clock disagreement), is never a stall.
 */
class RunStallTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    @Test
    void theWindowIsThirtyMinutes() {
        assertThat(RunStall.STALLED_AFTER).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void anInFlightRunIsStalledStrictlyAfterThirtyMinutes() {
        for (JobStatus inFlight : JobStatus.IN_FLIGHT) {
            assertThat(RunStall.isStalled(inFlight, NOW.minusMinutes(30), NOW)).as(inFlight.name()).isFalse();
            assertThat(RunStall.isStalled(inFlight, NOW.minusMinutes(30).minusNanos(1_000_000), NOW)).as(inFlight.name()).isTrue();
        }
    }

    @Test
    void aFinishedRunIsNeverStalled() {
        for (JobStatus status : JobStatus.values()) {
            if (!status.isInFlight()) {
                assertThat(RunStall.isStalled(status, NOW.minusDays(3), NOW)).as(status.name()).isFalse();
            }
        }
    }

    @Test
    void noTimeOrAFutureTimeIsNeverAStall() {
        assertThat(RunStall.isStalled(JobStatus.Start, null, NOW)).isFalse();
        assertThat(RunStall.isStalled(JobStatus.Start, NOW.plusHours(5), NOW)).isFalse();
        assertThat(RunStall.isStalled(null, NOW.minusDays(1), NOW)).isFalse();
    }

    /** Every job the console lists carries the server's verdict, so both frontends read one rule. */
    @Test
    void aJobRowCarriesTheVerdict() throws Exception {
        SourceJobDto job = new SourceJobDto();
        job.setJobRunningStatus(JobStatus.Start);
        job.setLastJobRun(BusinessTime.now().minusHours(2));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

        assertThat(json.readTree(json.writeValueAsString(job)).get("stalled").asBoolean()).isTrue();

        job.setJobRunningStatus(JobStatus.Completed);
        assertThat(json.readTree(json.writeValueAsString(job)).get("stalled").asBoolean()).isFalse();
    }
}
