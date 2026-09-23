package process.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.billing.BillingService;
import process.model.dto.ResponseDto;
import process.model.pojo.BillingAccount;
import process.model.pojo.BillingDocument;
import process.model.pojo.Invoice;
import process.security.TenantContext;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every amount on the Documents screen was shown in dollars, because a document row carried no
 * currency and the console fell back to USD. A document's amount is in its invoice's currency;
 * a statement, which belongs to no invoice, is in the workspace's billing currency.
 */
class BillingDocumentCurrencyTest {

    private final BillingService billing = mock(BillingService.class);
    private final BillingRestApi api = new BillingRestApi(null, null, null, this.billing);

    @BeforeEach
    void platformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aDocumentCarriesTheCurrencyItsAmountIsIn() {
        Invoice gbpInvoice = new Invoice();
        gbpInvoice.setInvoiceId(10L);
        gbpInvoice.setNumber("INV-2026-09-0001");
        gbpInvoice.setCurrency("GBP");
        BillingAccount euroAccount = new BillingAccount();
        euroAccount.setCurrency("EUR");

        when(this.billing.documentsFor(any())).thenReturn(Arrays.asList(
            document(1L, 2905L, 10L, "receipt"),
            document(2L, 2905L, null, "statement")));
        when(this.billing.find(10L)).thenReturn(gbpInvoice);
        when(this.billing.accountFor(anyLong())).thenReturn(euroAccount);
        when(this.billing.userNames(any())).thenReturn(Collections.emptyMap());
        when(this.billing.tenantNames()).thenReturn(Collections.emptyMap());

        List<Map<String, Object>> rows = rows(this.api.documents(null));

        assertThat(rows.get(0).get("currency")).isEqualTo("GBP");
        assertThat(rows.get(1).get("currency")).isEqualTo("EUR");
    }

    private static BillingDocument document(Long id, Long tenantId, Long invoiceId, String kind) {
        BillingDocument d = new BillingDocument();
        d.setBillingDocumentId(id);
        d.setTenantId(tenantId);
        d.setInvoiceId(invoiceId);
        d.setKind(kind);
        d.setAmount(new BigDecimal("120.00"));
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ResponseEntity<?> response) {
        return (List<Map<String, Object>>) ((ResponseDto) response.getBody()).getData();
    }
}
