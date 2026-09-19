package process.billing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.pojo.BillingAccount;
import process.model.pojo.BillingDocument;
import process.model.pojo.Invoice;
import process.model.pojo.InvoiceLine;
import process.model.pojo.Payment;
import process.model.pojo.Tenant;
import process.model.repository.BillingAccountRepository;
import process.model.repository.BillingDocumentRepository;
import process.model.repository.InvoiceLineRepository;
import process.model.repository.InvoiceRepository;
import process.model.repository.PaymentRepository;
import process.model.repository.TenantRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The money rules: a draft is the meter's lines priced and totalled; tax only with a number and
 * a rate; issue freezes, numbers and renders; a verified payment moves the balance and writes a
 * receipt; a credit note is a negative invoice applied to the original.
 */
@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final long TENANT = 2905L;

    @Mock private MeterClient meter;
    @Mock private TenantRepository tenants;
    @Mock private StorageBrowserService storage;
    @Mock private UserNameResolver names;

    /** In-memory repositories: the rules under test are arithmetic and state, not SQL. */
    private final Map<Long, Invoice> invoiceRows = new LinkedHashMap<>();
    private final Map<Long, InvoiceLine> lineRows = new LinkedHashMap<>();
    private final Map<Long, Payment> paymentRows = new LinkedHashMap<>();
    private final List<BillingDocument> docRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private BillingAccount account;
    private BillingService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        BillingAccountRepository accounts = mock(BillingAccountRepository.class);
        this.account = new BillingAccount();
        this.account.setBillingAccountId(1L); this.account.setTenantId(TENANT); this.account.setLegalName("MedAxis Care Network Ltd");
        this.account.setCurrency("USD"); this.account.setPaymentTermsDays(30); this.account.setTaxRatePercent(BigDecimal.ZERO);
        lenient().when(accounts.findByTenantId(TENANT)).thenReturn(Optional.of(this.account));
        lenient().when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvoiceRepository invoices = mock(InvoiceRepository.class);
        lenient().when(invoices.save(any())).thenAnswer(inv -> { Invoice i = inv.getArgument(0); if (i.getInvoiceId() == null) i.setInvoiceId(this.ids.incrementAndGet()); this.invoiceRows.put(i.getInvoiceId(), i); return i; });
        lenient().when(invoices.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(this.invoiceRows.get(inv.<Long>getArgument(0))));
        lenient().when(invoices.findByNumber(anyString())).thenAnswer(inv -> this.invoiceRows.values().stream().filter(i -> i.getNumber().equals(inv.getArgument(0))).findFirst());
        lenient().when(invoices.countByNumberPrefix(anyString())).thenAnswer(inv -> this.invoiceRows.values().stream().filter(i -> i.getNumber().startsWith(inv.getArgument(0))).count());
        lenient().when(invoices.findFirstByTenantIdAndPeriodStartAndKindAndStatus(anyLong(), any(), anyString(), anyString())).thenAnswer(inv ->
            this.invoiceRows.values().stream().filter(i -> i.getTenantId().equals(inv.getArgument(0)) && i.getPeriodStart().equals(inv.getArgument(1)) && i.getKind().equals(inv.getArgument(2)) && i.getStatus().equals(inv.getArgument(3))).findFirst());
        lenient().when(invoices.findByStatusIn(any())).thenAnswer(inv -> { List<String> s = inv.getArgument(0); List<Invoice> out = new ArrayList<>(); for (Invoice i : this.invoiceRows.values()) if (s.contains(i.getStatus())) out.add(i); return out; });
        lenient().when(invoices.findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(anyLong())).thenAnswer(inv -> new ArrayList<>(this.invoiceRows.values()));

        InvoiceLineRepository lines = mock(InvoiceLineRepository.class);
        lenient().when(lines.save(any())).thenAnswer(inv -> { InvoiceLine l = inv.getArgument(0); if (l.getInvoiceLineId() == null) l.setInvoiceLineId(this.ids.incrementAndGet()); this.lineRows.put(l.getInvoiceLineId(), l); return l; });
        lenient().when(lines.saveAll(any())).thenAnswer(inv -> { for (InvoiceLine l : inv.<List<InvoiceLine>>getArgument(0)) { if (l.getInvoiceLineId() == null) l.setInvoiceLineId(this.ids.incrementAndGet()); this.lineRows.put(l.getInvoiceLineId(), l); } return inv.getArgument(0); });
        lenient().when(lines.findByInvoiceIdOrderBySortAsc(anyLong())).thenAnswer(inv -> { List<InvoiceLine> out = new ArrayList<>(); for (InvoiceLine l : this.lineRows.values()) if (l.getInvoiceId().equals(inv.getArgument(0))) out.add(l); return out; });
        lenient().doAnswer(inv -> { this.lineRows.values().removeIf(l -> l.getInvoiceId().equals(inv.getArgument(0))); return null; }).when(lines).deleteByInvoiceId(anyLong());

        PaymentRepository payments = mock(PaymentRepository.class);
        lenient().when(payments.save(any())).thenAnswer(inv -> { Payment p = inv.getArgument(0); if (p.getPaymentId() == null) p.setPaymentId(this.ids.incrementAndGet()); this.paymentRows.put(p.getPaymentId(), p); return p; });
        lenient().when(payments.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(this.paymentRows.get(inv.<Long>getArgument(0))));
        lenient().when(payments.findByInvoiceIdOrderByDateCreatedAsc(anyLong())).thenAnswer(inv -> { List<Payment> out = new ArrayList<>(); for (Payment p : this.paymentRows.values()) if (p.getInvoiceId().equals(inv.getArgument(0))) out.add(p); return out; });
        lenient().when(payments.findAll()).thenAnswer(inv -> new ArrayList<>(this.paymentRows.values()));

        BillingDocumentRepository documents = mock(BillingDocumentRepository.class);
        lenient().when(documents.save(any())).thenAnswer(inv -> { BillingDocument d = inv.getArgument(0); d.setBillingDocumentId(this.ids.incrementAndGet()); this.docRows.add(d); return d; });

        Tenant tenant = new Tenant(); tenant.setTenantId(TENANT); tenant.setTenantName("MedAxis Care Network");
        lenient().when(this.tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        lenient().when(this.meter.isConfigured()).thenReturn(true);
        lenient().when(this.meter.rateCard()).thenReturn(map("version", 1));
        lenient().when(this.meter.usage(eq(TENANT), any(), any(), eq("meter"))).thenReturn(map("rows", Arrays.asList(
            map("meter", "seats.user_days", "label", "Seats", "unit", "user-day", "per", 1, "unitPrice", "0.33", "quantity", "140", "amount", "46.2"),
            map("meter", "storage.bytes.deleted", "label", "Bytes deleted (data churn)", "unit", "byte", "per", 1073741824, "unitPrice", "0.01", "quantity", "41016604262", "amount", "0.382"),
            map("meter", "storage.bytes.read", "label", "Bytes read", "unit", "byte", "per", 1073741824, "unitPrice", "0", "quantity", "0", "amount", "0"))));

        this.service = new BillingService(this.meter, accounts, invoices, lines, payments, documents, this.tenants, this.storage, this.names, "etl-config");
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aDraftIsTheMetersLinesTotalledWithNoTaxUnlessTheAccountCarriesANumberAndARate() {
        Invoice draft = this.service.draft(TENANT, YearMonth.of(2026, 9));
        assertThat(draft.getNumber()).isEqualTo("INV-2026-09-0001");
        assertThat(draft.getStatus()).isEqualTo("draft");
        assertThat(this.service.linesOf(draft.getInvoiceId())).extracting(InvoiceLine::getMeter).containsExactly("seats.user_days", "storage.bytes.deleted");
        assertThat(draft.getSubtotal()).isEqualByComparingTo("46.58");
        assertThat(draft.getTax()).isEqualByComparingTo("0");
        assertThat(draft.getTotal()).isEqualByComparingTo("46.58");
        assertThat(draft.getRateCardVersion()).isEqualTo(1);

        // A tax number alone is not tax; a number and a rate are.
        this.account.setTaxId("GB123456789");
        assertThat(BillingService.taxApplies(this.account)).isFalse();
        this.account.setTaxRatePercent(new BigDecimal("20")); this.account.setTaxLabel("VAT");
        Invoice again = this.service.draft(TENANT, YearMonth.of(2026, 9));
        assertThat(again.getInvoiceId()).isEqualTo(draft.getInvoiceId());          // the same draft, rebuilt
        assertThat(again.getNumber()).isEqualTo("INV-2026-09-0001");
        assertThat(again.getTax()).isEqualByComparingTo("9.32");
        assertThat(again.getTotal()).isEqualByComparingTo("55.90");
    }

    @Test
    void issueFreezesNumbersAndRendersAndAManualLineSurvivesARedraft() throws Exception {
        Invoice draft = this.service.draft(TENANT, YearMonth.of(2026, 9));
        this.service.addManualLine(draft.getInvoiceId(), "Onboarding support (2 h)", new BigDecimal("2"), new BigDecimal("3.02"));
        Invoice redrafted = this.service.draft(TENANT, YearMonth.of(2026, 9));
        assertThat(this.service.linesOf(redrafted.getInvoiceId())).extracting(InvoiceLine::getDescription).contains("Onboarding support (2 h)");
        assertThat(redrafted.getTotal()).isEqualByComparingTo("52.62");

        Invoice issued = this.service.issue(redrafted.getInvoiceId());
        assertThat(issued.getStatus()).isEqualTo("issued");
        assertThat(issued.getIssuedAt()).isNotNull();
        assertThat(issued.getDueAt().getTime() - issued.getIssuedAt().getTime()).isEqualTo(30L * 86_400_000);
        assertThat(issued.getBalance()).isEqualByComparingTo("52.62");
        assertThat(issued.getPdfObjectKey()).startsWith("billing/2905/").endsWith("INV-2026-09-0001.pdf");
        assertThat(this.docRows).extracting(BillingDocument::getKind).containsExactly("invoice");
        assertThat(this.docRows.get(0).getSizeBytes()).isGreaterThan(1000L);
        assertThatThrownBy(() -> this.service.addManualLine(issued.getInvoiceId(), "late", BigDecimal.ONE, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only a draft");
    }

    @Test
    void aSubmittedPaymentCountsOnlyOnceVerifiedAndThenWritesAReceipt() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        TenantContext.set(TENANT, "TENANT_ADMIN", 4385L, "emily@medaxis");
        Payment submitted = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("20"), "bank", "TRF-1", null, null);
        assertThat(submitted.getStatus()).isEqualTo("submitted");
        assertThat(this.service.find(issued.getInvoiceId()).getBalance()).isEqualByComparingTo("46.58");   // nothing moved yet

        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        Payment verified = this.service.verifyPayment(submitted.getPaymentId(), true, null);
        assertThat(verified.getStatus()).isEqualTo("verified");
        assertThat(verified.getReceiptNumber()).startsWith("RCP-");
        Invoice partly = this.service.find(issued.getInvoiceId());
        assertThat(partly.getStatus()).isEqualTo("partially_paid");
        assertThat(partly.getBalance()).isEqualByComparingTo("26.58");
        assertThat(this.docRows).extracting(BillingDocument::getKind).containsExactly("invoice", "receipt");

        Payment rest = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("26.58"), "bank", "TRF-2", null, null);
        this.service.verifyPayment(rest.getPaymentId(), true, null);
        Invoice paid = this.service.find(issued.getInvoiceId());
        assertThat(paid.getStatus()).isEqualTo("paid");
        assertThat(paid.getBalance()).isEqualByComparingTo("0");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bank", null, null, null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not open for payment");
    }

    @Test
    void aRejectedPaymentMovesNothing() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Payment p = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("46.58"), "bank", "TRF-X", null, null);
        this.service.verifyPayment(p.getPaymentId(), false, "no transfer received");
        assertThat(this.service.find(issued.getInvoiceId()).getBalance()).isEqualByComparingTo("46.58");
        assertThat(this.service.find(issued.getInvoiceId()).getStatus()).isEqualTo("issued");
        assertThatThrownBy(() -> this.service.verifyPayment(p.getPaymentId(), true, null)).hasMessageContaining("already rejected");
    }

    @Test
    void aCreditNoteIsANegativeInvoiceAppliedToTheOriginal() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Invoice note = this.service.creditNote(issued.getInvoiceId(), new BigDecimal("6.58"), "duplicate conversion runs");
        assertThat(note.getKind()).isEqualTo("credit_note");
        assertThat(note.getNumber()).isEqualTo("CN-2026-09-0001");
        assertThat(note.getTotal()).isEqualByComparingTo("-6.58");
        assertThat(note.getReferencesInvoiceId()).isEqualTo(issued.getInvoiceId());
        Invoice original = this.service.find(issued.getInvoiceId());
        assertThat(original.getBalance()).isEqualByComparingTo("40.00");
        assertThat(original.getStatus()).isEqualTo("partially_paid");
        assertThat(this.docRows).extracting(BillingDocument::getKind).containsExactly("invoice", "credit_note");
        assertThatThrownBy(() -> this.service.voidInvoice(issued.getInvoiceId(), "x")).hasMessageContaining("partly paid");
    }

    @Test
    void overdueIsIssuedPastDueWithABalance() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        assertThat(this.service.markOverdue()).isEqualTo(0);
        issued.setDueAt(new java.sql.Timestamp(System.currentTimeMillis() - 86_400_000));
        assertThat(this.service.markOverdue()).isEqualTo(1);
        assertThat(this.service.find(issued.getInvoiceId()).getStatus()).isEqualTo("overdue");
    }

    @Test
    void aStatementListsTheInvoicesAndPaymentsOfARange() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Payment p = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("10"), "bank", "TRF-9", null, null);
        this.service.verifyPayment(p.getPaymentId(), true, null);
        BillingDocument statement = this.service.statement(TENANT, java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now().plusDays(1));
        assertThat(statement.getKind()).isEqualTo("statement");
        assertThat(statement.getAmount()).isEqualByComparingTo("36.58");      // the balance open
        assertThat(statement.getFileName()).endsWith(".pdf");
    }

    @Test
    void aZeroInvoiceIsPaidOnIssue() throws Exception {
        when(this.meter.usage(eq(TENANT), any(), any(), eq("meter"))).thenReturn(map("rows", new ArrayList<>()));
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 8)).getInvoiceId());
        assertThat(issued.getTotal()).isEqualByComparingTo("0");
        assertThat(issued.getStatus()).isEqualTo("paid");
    }
}
