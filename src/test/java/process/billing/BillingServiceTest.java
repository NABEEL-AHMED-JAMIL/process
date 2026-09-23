package process.billing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.InvoiceKind;
import process.model.enums.InvoiceStatus;
import process.model.enums.PaymentStatus;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import process.storage.TrustedStorageOperations;

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
    @Mock private TrustedStorageOperations storage;
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
        lenient().when(invoices.findFirstByTenantIdAndPeriodStartAndKindAndStatusNotIn(anyLong(), any(), anyString(), any())).thenAnswer(inv -> {
            List<String> excluded = inv.getArgument(3);
            return this.invoiceRows.values().stream().filter(i -> i.getTenantId().equals(inv.getArgument(0)) && i.getPeriodStart().equals(inv.getArgument(1))
                && i.getKind().equals(inv.getArgument(2)) && !excluded.contains(i.getStatus())).findFirst(); });
        lenient().when(invoices.findByStatusIn(any())).thenAnswer(inv -> { List<String> s = inv.getArgument(0); List<Invoice> out = new ArrayList<>(); for (Invoice i : this.invoiceRows.values()) if (s.contains(i.getStatus())) out.add(i); return out; });
        lenient().when(invoices.findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(anyLong())).thenAnswer(inv -> new ArrayList<>(this.invoiceRows.values()));
        lenient().when(invoices.findByPeriodStartBetweenOrderByTenantIdAscPeriodStartAsc(any(), any())).thenAnswer(inv -> new ArrayList<>(this.invoiceRows.values()));

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
        lenient().when(payments.countByReceiptNumberStartingWith(anyString())).thenAnswer(inv ->
            this.paymentRows.values().stream().filter(p -> p.getReceiptNumber() != null && p.getReceiptNumber().startsWith(inv.getArgument(0))).count());
        lenient().when(payments.existsByReceiptNumber(anyString())).thenAnswer(inv ->
            this.paymentRows.values().stream().anyMatch(p -> inv.getArgument(0).equals(p.getReceiptNumber())));
        lenient().when(payments.findByStatusOrderByDateCreatedAsc(anyString())).thenAnswer(inv -> { List<Payment> out = new ArrayList<>(); for (Payment p : this.paymentRows.values()) if (p.getStatus().equals(inv.getArgument(0))) out.add(p); return out; });

        BillingDocumentRepository documents = mock(BillingDocumentRepository.class);
        lenient().when(documents.save(any())).thenAnswer(inv -> { BillingDocument d = inv.getArgument(0); d.setBillingDocumentId(this.ids.incrementAndGet()); this.docRows.add(d); return d; });

        Tenant tenant = new Tenant(); tenant.setTenantId(TENANT); tenant.setTenantName("MedAxis Care Network");
        lenient().when(this.tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        lenient().when(this.tenants.existsById(TENANT)).thenReturn(true);
        lenient().when(invoices.findByReferencesInvoiceId(anyLong())).thenAnswer(inv -> { List<Invoice> out = new ArrayList<>(); for (Invoice i : this.invoiceRows.values()) if (inv.<Long>getArgument(0).equals(i.getReferencesInvoiceId())) out.add(i); return out; });
        lenient().when(this.meter.isConfigured()).thenReturn(true);
        lenient().when(this.meter.usage(eq(TENANT), any(), any(), eq("meter"))).thenReturn(map(
            "rateCard", map("version", 1, "name", "Standard", "tenantSpecific", false, "effectiveFrom", "2026-01-01"),
            "rows", Arrays.asList(
            map("meter", "seats.user_days", "label", "Seats", "unit", "user-day", "per", 1, "unitPrice", "0.33", "quantity", "140", "amount", "46.2",
                "includedQuantity", "10", "billableQuantity", "130", "tiers", Arrays.asList(map("from", 0, "to", 100, "units", 100, "unit_price", "0.4"), map("from", 100, "to", null, "units", 30, "unit_price", "0.2"))),
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
        assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT.value());
        assertThat(this.service.linesOf(draft.getInvoiceId())).extracting(InvoiceLine::getMeter).containsExactly("seats.user_days", "storage.bytes.deleted");
        assertThat(draft.getSubtotal()).isEqualByComparingTo("46.58");
        assertThat(draft.getTax()).isEqualByComparingTo("0");
        assertThat(draft.getTotal()).isEqualByComparingTo("46.58");
        assertThat(draft.getRateCardVersion()).isEqualTo(1);
        assertThat(draft.getRateCardName()).isEqualTo("Standard");
        // The calculation the meter applied is frozen with the line: allowance, billable, tier bands.
        InvoiceLine seats = this.service.linesOf(draft.getInvoiceId()).get(0);
        assertThat(seats.getIncludedQuantity()).isEqualByComparingTo("10");
        assertThat(seats.getBillableQuantity()).isEqualByComparingTo("130");
        assertThat(BillingService.tierBands(seats)).hasSize(2);
        assertThat(BillingService.tierBands(seats).get(1).get("to")).isNull();
        assertThat(BillingService.tierBands(this.service.linesOf(draft.getInvoiceId()).get(1))).isEmpty();

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
        assertThat(issued.getStatus()).isEqualTo(InvoiceStatus.ISSUED.value());
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
        assertThat(submitted.getStatus()).isEqualTo(PaymentStatus.SUBMITTED.value());
        assertThat(this.service.find(issued.getInvoiceId()).getBalance()).isEqualByComparingTo("46.58");   // nothing moved yet

        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        Payment verified = this.service.verifyPayment(submitted.getPaymentId(), true, null);
        assertThat(verified.getStatus()).isEqualTo(PaymentStatus.VERIFIED.value());
        assertThat(verified.getReceiptNumber()).startsWith("RCP-");
        Invoice partly = this.service.find(issued.getInvoiceId());
        assertThat(partly.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID.value());
        assertThat(partly.getBalance()).isEqualByComparingTo("26.58");
        assertThat(this.docRows).extracting(BillingDocument::getKind).containsExactly("invoice", "receipt");

        Payment rest = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("26.58"), "bank", "TRF-2", null, null);
        this.service.verifyPayment(rest.getPaymentId(), true, null);
        Invoice paid = this.service.find(issued.getInvoiceId());
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.PAID.value());
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
        assertThat(this.service.find(issued.getInvoiceId()).getStatus()).isEqualTo(InvoiceStatus.ISSUED.value());
        assertThatThrownBy(() -> this.service.verifyPayment(p.getPaymentId(), true, null)).hasMessageContaining("already rejected");
    }

    @Test
    void aCreditNoteIsANegativeInvoiceAppliedToTheOriginal() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Invoice note = this.service.creditNote(issued.getInvoiceId(), new BigDecimal("6.58"), "duplicate conversion runs");
        assertThat(note.getKind()).isEqualTo(InvoiceKind.CREDIT_NOTE.value());
        assertThat(note.getNumber()).isEqualTo("CN-2026-09-0001");
        assertThat(note.getTotal()).isEqualByComparingTo("-6.58");
        assertThat(note.getReferencesInvoiceId()).isEqualTo(issued.getInvoiceId());
        Invoice original = this.service.find(issued.getInvoiceId());
        assertThat(original.getBalance()).isEqualByComparingTo("40.00");
        assertThat(original.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID.value());
        assertThat(this.docRows).extracting(BillingDocument::getKind).containsExactly("invoice", "credit_note");
        assertThatThrownBy(() -> this.service.voidInvoice(issued.getInvoiceId(), "x")).hasMessageContaining("partly paid");
        // Never more than what was billed and not yet credited: 46.58 - 6.58 = 40.00 is the most left.
        assertThatThrownBy(() -> this.service.creditNote(issued.getInvoiceId(), new BigDecimal("40.01"), "too much"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 40.00");
        assertThatThrownBy(() -> this.service.creditNote(issued.getInvoiceId(), new BigDecimal("1"), "  ")).hasMessageContaining("reason is required");
        Invoice rest = this.service.creditNote(issued.getInvoiceId(), new BigDecimal("40"), "the rest");
        assertThat(rest.getTotal()).isEqualByComparingTo("-40");
        // A credit note is not an invoice: nothing is paid against it, nothing credited against it.
        assertThatThrownBy(() -> this.service.submitPayment(rest.getInvoiceId(), BigDecimal.ONE, "bank", null, null, null)).hasMessageContaining("not open for payment");
        assertThatThrownBy(() -> this.service.creditNote(rest.getInvoiceId(), BigDecimal.ONE, "x")).hasMessageContaining("Only an issued invoice");
        assertThat(this.service.find(issued.getInvoiceId()).getStatus()).isEqualTo(InvoiceStatus.PAID.value());
    }

    @Test
    void aCreditOnAPaidBillIsARefundOwedAndNeverCountsAsCollected() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Payment p = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("46.58"), "bank", "TRF-1", null, null);
        this.service.verifyPayment(p.getPaymentId(), true, null);
        // A credit against a paid bill: capped at what was billed, applied to nothing -- the bill stays paid in full.
        assertThatThrownBy(() -> this.service.creditNote(issued.getInvoiceId(), new BigDecimal("46.59"), "x")).hasMessageContaining("At most 46.58");
        Invoice refund = this.service.creditNote(issued.getInvoiceId(), new BigDecimal("10"), "overcharged");
        assertThat(refund.getTotal()).isEqualByComparingTo("-10");
        Invoice paid = this.service.find(issued.getInvoiceId());
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.PAID.value());
        assertThat(paid.getBalance()).isEqualByComparingTo("0");
        assertThat(this.service.paymentsOf(issued.getInvoiceId())).hasSize(1);       // no credit_note payment row
        // Analytics: invoiced is net of the credit, collected is the money that arrived.
        Map<String, Object> a = this.service.analytics(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat((BigDecimal) a.get("invoiced")).isEqualByComparingTo("36.58");
        assertThat((BigDecimal) a.get("collected")).isEqualByComparingTo("46.58");
        // And a credit applied to an open bill is not "collected" either.
        Invoice second = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 8)).getInvoiceId());
        this.service.creditNote(second.getInvoiceId(), new BigDecimal("6.58"), "goodwill");
        Map<String, Object> b = this.service.analytics(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));
        assertThat((BigDecimal) b.get("invoiced")).isEqualByComparingTo("76.58");    // 36.58 + 46.58 - 6.58
        assertThat((BigDecimal) b.get("collected")).isEqualByComparingTo("46.58");
        assertThat((BigDecimal) b.get("open")).isEqualByComparingTo("40.00");
    }

    @Test
    void whatARequestMayCarryIsBounded() throws Exception {
        Invoice draft = this.service.draft(TENANT, YearMonth.of(2026, 9));
        assertThatThrownBy(() -> this.service.draft(999999L, YearMonth.of(2026, 9))).hasMessageContaining("No such workspace");
        assertThatThrownBy(() -> this.service.addManualLine(draft.getInvoiceId(), "x", new BigDecimal("-1"), BigDecimal.ONE)).hasMessageContaining("quantity above zero");
        assertThatThrownBy(() -> this.service.addManualLine(draft.getInvoiceId(), "x", BigDecimal.ZERO, BigDecimal.ONE)).hasMessageContaining("quantity above zero");
        assertThatThrownBy(() -> this.service.addManualLine(draft.getInvoiceId(), "x", BigDecimal.ONE, new BigDecimal("1000000000000"))).hasMessageContaining("unit price");
        assertThatThrownBy(() -> this.service.addManualLine(draft.getInvoiceId(), new String(new char[301]).replace('\0', 'x'), BigDecimal.ONE, BigDecimal.ONE)).hasMessageContaining("at most 300");
        assertThatThrownBy(() -> this.service.addManualLine(draft.getInvoiceId(), " ", BigDecimal.ONE, BigDecimal.ONE)).hasMessageContaining("description is required");
        // A discount is a manual line with a negative price -- allowed, within reason.
        assertThat(this.service.addManualLine(draft.getInvoiceId(), "Loyalty discount", BigDecimal.ONE, new BigDecimal("-5")).getAmount()).isEqualByComparingTo("-5");

        Invoice issued = this.service.issue(draft.getInvoiceId());
        BigDecimal owed = issued.getBalance();
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), owed.add(new BigDecimal("0.01")), "bank", null, null, null)).hasMessageContaining("At most " + owed.toPlainString());
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bitcoin", null, null, null)).hasMessageContaining("payment method");
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bank", new String(new char[121]).replace('\0', 'r'), null, null)).hasMessageContaining("reference");
        // Two slips cannot together promise more than is owed.
        this.service.submitPayment(issued.getInvoiceId(), owed.subtract(BigDecimal.ONE), "bank", "TRF-A", null, null);
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("1.01"), "bank", "TRF-B", null, null)).hasMessageContaining("At most 1.00");
        assertThat(this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "CARD", "TRF-B", null, null).getMethod()).isEqualTo("card");
    }

    @Test
    void aSlipIsWhatItsBytesSayItIs() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        MockMultipartFile html = new MockMultipartFile("slip", "slip.png", "image/png", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bank", null, null, html)).hasMessageContaining("PDF, a PNG or a JPEG");
        byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
        MockMultipartFile traversal = new MockMultipartFile("slip", "../../etc/passwd\"; x.html", "text/html", png);
        Payment p = this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bank", null, null, traversal);
        BillingDocument slip = this.docRows.get(this.docRows.size() - 1);
        assertThat(slip.getKind()).isEqualTo("payment_slip");
        assertThat(slip.getContentType()).isEqualTo("image/png");                 // the bytes, not the browser's word
        assertThat(slip.getFileName()).matches("[A-Za-z0-9_][A-Za-z0-9._-]*\\.png");
        assertThat(slip.getFileName()).doesNotContain("/").doesNotStartWith(".");
        assertThat(p.getSlipObjectKey()).doesNotContain("..");
        byte[] big = new byte[(int) BillingService.SLIP_MAX_BYTES + 1]; System.arraycopy("%PDF-".getBytes(), 0, big, 0, 5);
        assertThatThrownBy(() -> this.service.submitPayment(issued.getInvoiceId(), BigDecimal.ONE, "bank", null, null, new MockMultipartFile("slip", "big.pdf", "application/pdf", big))).hasMessageContaining("at most 10 MB");
    }

    @Test
    void overdueIsIssuedPastDueWithABalance() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        assertThat(this.service.markOverdue()).isEqualTo(0);
        issued.setDueAt(new Timestamp(System.currentTimeMillis() - 86_400_000));
        assertThat(this.service.markOverdue()).isEqualTo(1);
        assertThat(this.service.find(issued.getInvoiceId()).getStatus()).isEqualTo(InvoiceStatus.OVERDUE.value());
    }

    @Test
    void aStatementListsTheInvoicesAndPaymentsOfARange() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Payment p = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("10"), "bank", "TRF-9", null, null);
        this.service.verifyPayment(p.getPaymentId(), true, null);
        BillingDocument statement = this.service.statement(TENANT, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(statement.getKind()).isEqualTo("statement");
        assertThat(statement.getAmount()).isEqualByComparingTo("36.58");      // the balance open
        assertThat(statement.getFileName()).endsWith(".pdf");
    }

    @Test
    void theSummaryIsTheBillInOneGlance() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        Payment p = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("10"), "bank", "TRF-1", null, null);
        Map<String, Object> s = this.service.summary(TENANT);
        assertThat(s.get("currency")).isEqualTo("USD");
        assertThat((BigDecimal) s.get("monthToDate")).isEqualByComparingTo("46.58");        // the meter's rows for this month
        assertThat(s.get("rateCardName")).isEqualTo("Standard");
        assertThat((BigDecimal) s.get("openBalance")).isEqualByComparingTo("46.58");        // the slip is not verified yet
        assertThat(s.get("openCount")).isEqualTo(1);
        assertThat(s.get("pendingSlips")).isEqualTo(1);
        assertThat(s.get("nextDueNumber")).isEqualTo(issued.getNumber());
        assertThat(s.get("latestNumber")).isEqualTo(issued.getNumber());
        this.service.verifyPayment(p.getPaymentId(), true, null);
        assertThat((BigDecimal) this.service.summary(TENANT).get("openBalance")).isEqualByComparingTo("36.58");
        assertThat(this.service.summary(TENANT).get("pendingSlips")).isEqualTo(0);
    }

    @Test
    void aZeroInvoiceIsPaidOnIssue() throws Exception {
        when(this.meter.usage(eq(TENANT), any(), any(), eq("meter"))).thenReturn(map("rows", new ArrayList<>()));
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 8)).getInvoiceId());
        assertThat(issued.getTotal()).isEqualByComparingTo("0");
        assertThat(issued.getStatus()).isEqualTo(InvoiceStatus.PAID.value());
    }

    // ---- one invoice per month (DEF-002) -------------------------------------------------------

    /**
     * draft() promised "an issued invoice for the month is left alone" and looked only for a
     * DRAFT to rebuild. Once the month was issued there was no draft, so it made a second one --
     * and a third on the next click. Dev held nine live invoices for one tenant's September.
     */
    @ParameterizedTest
    @EnumSource(value = InvoiceStatus.class,
        names = { "ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE" })
    void aMonthThatHasLeftDraftIsNotDraftedASecondTime(InvoiceStatus settled) throws Exception {
        YearMonth september = YearMonth.of(2026, 9);
        Invoice issued = this.service.issue(this.service.draft(TENANT, september).getInvoiceId());
        issued.setStatus(settled.value());

        assertThatThrownBy(() -> this.service.draft(TENANT, september))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(issued.getNumber())
            .hasMessageContaining("void it");
        assertThat(this.liveInvoicesFor(september)).containsExactly(issued.getNumber());
    }

    /** Voiding is how a month is legitimately re-billed, so a voided month drafts again, under a new number. */
    @Test
    void aVoidedMonthIsDraftedAgainUnderANewNumber() throws Exception {
        YearMonth september = YearMonth.of(2026, 9);
        Invoice issued = this.service.issue(this.service.draft(TENANT, september).getInvoiceId());
        this.service.voidInvoice(issued.getInvoiceId(), "wrong rate card");

        Invoice redrafted = this.service.draft(TENANT, september);

        assertThat(redrafted.getInvoiceId()).isNotEqualTo(issued.getInvoiceId());
        assertThat(redrafted.getNumber()).isNotEqualTo(issued.getNumber());
        assertThat(redrafted.getStatus()).isEqualTo(InvoiceStatus.DRAFT.value());
        assertThat(this.liveInvoicesFor(september)).containsExactly(redrafted.getNumber());
    }

    /** Another month is another invoice: the guard is per month, not per workspace. */
    @Test
    void anIssuedMonthDoesNotStopTheNextOneBeingDrafted() throws Exception {
        this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 8)).getInvoiceId());

        Invoice september = this.service.draft(TENANT, YearMonth.of(2026, 9));

        assertThat(september.getStatus()).isEqualTo(InvoiceStatus.DRAFT.value());
        assertThat(this.liveInvoicesFor(YearMonth.of(2026, 9))).containsExactly(september.getNumber());
    }

    // ---- one number per receipt (DEF-001) ------------------------------------------------------

    /**
     * The receipt number was every payment row counted, plus one -- submitted and rejected ones
     * included. Two slips submitted before either was verified left the count at two for both
     * verifications, so both receipts came out the same. No concurrency was needed; dev carried
     * two such pairs from ordinary use, on documents a customer files for tax.
     */
    @Test
    void twoSlipsVerifiedOneAfterTheOtherGetDifferentReceiptNumbers() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        TenantContext.set(TENANT, "TENANT_ADMIN", 4385L, "emily@medaxis");
        Payment first = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("10"), "bank", "TRF-1", null, null);
        Payment second = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("10"), "bank", "TRF-2", null, null);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        String a = this.service.verifyPayment(first.getPaymentId(), true, null).getReceiptNumber();
        String b = this.service.verifyPayment(second.getPaymentId(), true, null).getReceiptNumber();

        assertThat(a).isNotEqualTo(b);
    }

    /** A slip that was rejected never had a receipt, so it takes no place in the sequence. */
    @Test
    void theFirstReceiptOfAMonthIsNumberOneWhateverElseWasSubmitted() throws Exception {
        Invoice issued = this.service.issue(this.service.draft(TENANT, YearMonth.of(2026, 9)).getInvoiceId());
        TenantContext.set(TENANT, "TENANT_ADMIN", 4385L, "emily@medaxis");
        Payment rejected = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("5"), "bank", "TRF-X", null, null);
        Payment accepted = this.service.submitPayment(issued.getInvoiceId(), new BigDecimal("5"), "bank", "TRF-Y", null, null);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        this.service.verifyPayment(rejected.getPaymentId(), false, "unreadable slip");

        String receipt = this.service.verifyPayment(accepted.getPaymentId(), true, null).getReceiptNumber();

        assertThat(receipt).startsWith("RCP-").endsWith("-0001");
    }

    private List<String> liveInvoicesFor(YearMonth period) {
        List<String> out = new ArrayList<>();
        for (Invoice i : this.invoiceRows.values()) {
            if (InvoiceKind.INVOICE.is(i.getKind()) && i.getPeriodStart().equals(period.atDay(1))
                && !InvoiceStatus.VOID.is(i.getStatus())) {
                out.add(i.getNumber());
            }
        }
        return out;
    }

    // ---- MIG-78: the rounding sequence and the tax gate, pinned before Billing is carved ----------

    /** The meter's answer for a month, with only these rows. */
    private void meterAnswers(Map<String, Object>... rows) {
        when(this.meter.usage(eq(TENANT), any(), any(), eq("meter"))).thenReturn(map(
            "rateCard", map("version", 1, "name", "Standard"), "rows", Arrays.asList(rows)));
    }

    private static Map<String, Object> row(String meter, String quantity, String amount) {
        return map("meter", meter, "label", meter, "unit", "op", "per", 1, "unitPrice", "0.001", "quantity", quantity, "amount", amount);
    }

    /**
     * The meter quantises each line to 5 places (half-even, on its side); the console sums the lines
     * at full precision and rounds ONCE, to 2 places HALF_UP, at the subtotal. Three lines of a third
     * of a cent are a cent; rounding each first would make them nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSubtotalIsSummedAtFullPrecisionAndRoundedOnceHalfUp() {
        this.meterAnswers(row("storage.ops.read", "3", "0.00333"), row("storage.ops.write", "3", "0.00333"), row("storage.ops.delete", "3", "0.00334"));
        assertThat(this.service.draft(TENANT, YearMonth.of(2026, 9)).getSubtotal()).isEqualByComparingTo("0.01");

        // Half-UP at the subtotal: 0.005 is a cent (half-even would make it nothing).
        this.meterAnswers(row("storage.ops.read", "5", "0.005"));
        assertThat(this.service.draft(TENANT, YearMonth.of(2026, 9)).getSubtotal()).isEqualByComparingTo("0.01");
    }

    /**
     * Tax is computed on the ROUNDED subtotal, to 2 places HALF_UP, and the total is subtotal plus tax
     * with no further rounding. 0.125 rounds to 0.13, half of which is 0.065 and rounds to 0.07; tax on
     * the unrounded 0.125 would have been 0.06.
     */
    @Test
    @SuppressWarnings("unchecked")
    void taxIsTakenOnTheRoundedSubtotalAndTheTotalIsTheirSum() {
        this.account.setTaxId("GB123456789");
        this.account.setTaxRatePercent(new BigDecimal("50"));
        this.meterAnswers(row("storage.ops.read", "125", "0.125"));

        Invoice draft = this.service.draft(TENANT, YearMonth.of(2026, 9));

        assertThat(draft.getSubtotal()).isEqualByComparingTo("0.13");
        assertThat(draft.getTax()).isEqualByComparingTo("0.07");
        assertThat(draft.getTotal()).isEqualByComparingTo("0.20");
        assertThat(draft.getTotal().scale()).isEqualTo(2);
    }

    /** Tax needs BOTH a non-blank tax number AND a rate above zero; either alone is no tax. */
    @Test
    void theTaxGateWantsANumberAndAPositiveRate() {
        this.account.setTaxId("   ");
        this.account.setTaxRatePercent(new BigDecimal("20"));
        assertThat(BillingService.taxApplies(this.account)).as("a blank number").isFalse();
        this.account.setTaxId("GB123456789");
        this.account.setTaxRatePercent(BigDecimal.ZERO);
        assertThat(BillingService.taxApplies(this.account)).as("a zero rate").isFalse();
        this.account.setTaxRatePercent(new BigDecimal("-5"));
        assertThat(BillingService.taxApplies(this.account)).as("a negative rate").isFalse();
        this.account.setTaxRatePercent(null);
        assertThat(BillingService.taxApplies(this.account)).as("no rate").isFalse();
        this.account.setTaxRatePercent(new BigDecimal("0.01"));
        assertThat(BillingService.taxApplies(this.account)).isTrue();
    }

    /** With no tax, an invoice says so in a row of its own; a credit note carries no such row. */
    @Test
    @SuppressWarnings("unchecked")
    void anInvoiceWithoutTaxSaysTaxNotAppliedAndACreditNoteDoesNot() {
        this.meterAnswers(row("storage.ops.read", "3000", "0.012"));
        Invoice invoice = this.service.draft(TENANT, YearMonth.of(2026, 9));

        BillingPdf.Doc doc = this.service.invoiceDoc(invoice, this.service.linesOf(invoice.getInvoiceId()), this.account);
        assertThat(doc.totals).extracting(t -> t[0] + " / " + t[1]).contains("Tax / not applied");

        invoice.setKind(InvoiceKind.CREDIT_NOTE.value());
        BillingPdf.Doc credit = this.service.invoiceDoc(invoice, this.service.linesOf(invoice.getInvoiceId()), this.account);
        assertThat(credit.totals).extracting(t -> t[0]).doesNotContain("Tax");
    }

    /**
     * A row is dropped only when its amount AND its quantity are both zero: free usage the customer
     * had (bytes read at 0.0 a GB) stays on the invoice at 0.00, because they see it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void freeUsageStaysOnTheInvoiceAndOnlyAnEmptyRowIsDropped() {
        this.meterAnswers(row("storage.bytes.read", "40", "0"), row("storage.ops.read", "0", "0"), row("pipeline.runs", "1", "0.002"));

        Invoice draft = this.service.draft(TENANT, YearMonth.of(2026, 9));

        assertThat(this.service.linesOf(draft.getInvoiceId())).extracting(InvoiceLine::getMeter)
            .containsExactly("storage.bytes.read", "pipeline.runs");
        assertThat(this.service.linesOf(draft.getInvoiceId()).get(0).getAmount()).isEqualByComparingTo("0");
    }

}
