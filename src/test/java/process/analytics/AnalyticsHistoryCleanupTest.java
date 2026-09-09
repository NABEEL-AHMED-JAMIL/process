package process.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import process.engine.cron.AnalyticsHistoryCleanupCron;
import process.model.repository.AnalyticsQueryRunRepository;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retention over analytics_query_run: the mechanism document 15 asks for, off until asked.
 *
 * <b>The first test is the important one.</b> V32__analytics_query.sql argued for leaving this
 * table unpruned and named the condition that would change its mind; shipping a cleanup that
 * deletes by default would overrule a deliberate decision with a default value, and the audit
 * rows it discarded could not be recovered. So the default is tested, not just written.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsHistoryCleanupTest {

    @Mock private AnalyticsQueryRunRepository repository;

    private AnalyticsHistoryCleanupCron cron(int retentionDays) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "historyRetentionDays", retentionDays);
        return new AnalyticsHistoryCleanupCron(this.repository, limits);
    }

    @Test
    void deletesNothingByDefault() {
        // Zero is the shipped default. Audit rows are not discarded because nobody set a policy.
        cron(0).removeExpiredHistory();
        verify(this.repository, never()).deleteByDateCreatedBefore(any());
    }

    @Test
    void theSHIPPEDdefaultIsOff() throws Exception {
        // The test above proves the cron does nothing when handed 0; it does NOT prove that 0 is
        // what production hands it, because it sets the field directly and never goes near the
        // annotation. Changing the @Value default to 90 left that test green -- checked by
        // mutation -- so the property default is asserted here, where a change to it is caught.
        //
        // Reading the annotation rather than booting Spring: this is one string, and a context
        // for it would be slower than the rest of this class by three orders of magnitude.
        Value annotation = AnalyticsLimits.class
            .getDeclaredField("historyRetentionDays").getAnnotation(Value.class);
        assertNotNull(annotation, "retention must come from a property, not a hardcoded field");
        assertEquals("${analytics.history.retention-days:0}", annotation.value(),
            "the shipped default must keep every audit row -- see V32__analytics_query.sql");
    }

    @Test
    void deletesNothingWhenRetentionIsNegative() {
        // A misconfigured negative must mean "keep everything", not "delete everything".
        cron(-1).removeExpiredHistory();
        verify(this.repository, never()).deleteByDateCreatedBefore(any());
    }

    @Test
    void deletesOlderThanTheWindowWhenOneIsSet() {
        when(this.repository.deleteByDateCreatedBefore(any())).thenReturn(12);

        cron(90).removeExpiredHistory();

        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(this.repository, times(1)).deleteByDateCreatedBefore(cutoff.capture());

        long expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90);
        long actual = cutoff.getValue().getTime();
        // Within a minute: the cut-off is computed from the clock at run time.
        assertTrue(Math.abs(expected - actual) < 60_000L,
            "cut-off should be 90 days back, was " + (expected - actual) + "ms out");
    }

    @Test
    void aFailedDeleteDoesNotEscape() {
        // This runs on the same scheduler as the job engine. A retention delete that threw would
        // take a scheduled thread with it, and the next hour would have tried again anyway.
        doThrow(new RuntimeException("deadlock detected"))
            .when(this.repository).deleteByDateCreatedBefore(any());

        cron(30).removeExpiredHistory();
    }
}
