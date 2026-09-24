package process.api;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import process.billing.BillingNumbers;
import process.billing.BillingService;
import process.billing.MeterClient;
import process.engine.cron.UsageMeasurerCron;
import process.model.dto.ResponseDto;
import process.util.UserNameResolver;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-9: if two documents still meet on one number, the caller gets a refusal it can act on --
 * "try again" -- not the generic failure sentence that hides a constraint-violation stack trace.
 */
class BillingNumberCollisionTest {

    private final BillingService billing = mock(BillingService.class);
    private final BillingRestApi api = new BillingRestApi(mock(MeterClient.class), mock(UsageMeasurerCron.class), mock(UserNameResolver.class), this.billing);

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement", new PSQLException(
            "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", PSQLState.UNIQUE_VIOLATION));
    }

    @Test
    void aNumberCollisionIsARetryableRefusal() {
        when(this.billing.draft(anyLong(), any(YearMonth.class))).thenThrow(uniqueViolation("invoice_number_key"));

        ResponseEntity<?> answer = this.api.draft(2905L, "2026-09");

        assertThat(((ResponseDto) answer.getBody()).getMessage()).isEqualTo(BillingNumbers.COLLISION);
    }

    @Test
    void anyOtherConstraintKeepsTheGenericSentence() {
        when(this.billing.draft(anyLong(), any(YearMonth.class))).thenThrow(uniqueViolation("ux_invoice_one_per_tenant_month"));

        ResponseEntity<?> answer = this.api.draft(2905L, "2026-09");

        assertThat(((ResponseDto) answer.getBody()).getMessage()).isNotEqualTo(BillingNumbers.COLLISION);
    }
}
