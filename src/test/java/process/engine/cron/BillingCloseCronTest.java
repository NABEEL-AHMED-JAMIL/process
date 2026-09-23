package process.engine.cron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.billing.BillingService;
import process.billing.MeterClient;
import process.model.pojo.Invoice;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The month-end close drafts every workspace's last month, skips the ones already invoiced, and
 * never lets one workspace's failure stop the rest.
 */
@ExtendWith(MockitoExtension.class)
class BillingCloseCronTest {

    @Mock private BillingService billing;
    @Mock private MeterClient meter;

    private final YearMonth last = YearMonth.now().minusMonths(1);

    private Map<Long, String> workspaces(Long... ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long id : ids) names.put(id, "workspace " + id);
        return names;
    }

    @Test
    void aMonthAlreadyInvoicedIsSkippedRatherThanDraftedAgain() {
        when(this.meter.isConfigured()).thenReturn(true);
        when(this.billing.tenantNames()).thenReturn(this.workspaces(1L, 2L));
        when(this.billing.settledInvoiceFor(1L, this.last)).thenReturn(Optional.of(new Invoice()));
        when(this.billing.settledInvoiceFor(2L, this.last)).thenReturn(Optional.empty());

        new BillingCloseCron(this.billing, this.meter).closeLastMonth();

        verify(this.billing, never()).draft(eq(1L), any());
        verify(this.billing).draft(2L, this.last);
    }

    @Test
    void oneWorkspaceFailingDoesNotStopTheOthers() {
        when(this.meter.isConfigured()).thenReturn(true);
        when(this.billing.tenantNames()).thenReturn(this.workspaces(1L, 2L, 3L));
        when(this.billing.settledInvoiceFor(any(), any())).thenReturn(Optional.empty());
        when(this.billing.draft(2L, this.last)).thenThrow(new IllegalStateException("meter down"));

        new BillingCloseCron(this.billing, this.meter).closeLastMonth();

        verify(this.billing).draft(1L, this.last);
        verify(this.billing).draft(3L, this.last);
    }

    @Test
    void withoutAMeterNothingIsDrafted() {
        when(this.meter.isConfigured()).thenReturn(false);

        new BillingCloseCron(this.billing, this.meter).closeLastMonth();

        verify(this.billing, never()).draft(any(), any());
    }
}
