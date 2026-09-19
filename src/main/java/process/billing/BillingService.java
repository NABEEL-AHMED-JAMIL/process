package process.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import process.config.StoragePropertyDefaults;
import process.model.dto.ObjectContentDto;
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

import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Invoices, payments and documents, on top of the meter.
 *
 * A month closes into a DRAFT built from the meter's priced lines; an admin may add a line or a
 * note; ISSUE freezes it, numbers it, renders the PDF and starts the clock to the due date. A
 * workspace pays by uploading a SLIP (the payment is `submitted`); the platform VERIFIES it,
 * which reduces the balance and issues a RECEIPT, or rejects it. Unpaid past due is OVERDUE.
 * Disputes become CREDIT NOTES -- negative invoices that reference the original and count as a
 * payment against it. Statements are rendered on demand. Every document is an object under
 * billing/ in the platform's config bucket and a row in billing_document; the bytes are served
 * through this service so the tenant never needs the bucket.
 *
 * Tax: applied only when the billing account carries a tax number and a rate; otherwise the
 * invoice says so with a 0 % line, which is the user's rule.
 */
@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    static final String DRAFT = "draft", ISSUED = "issued", PARTIAL = "partially_paid", PAID = "paid", OVERDUE = "overdue", VOID = "void";
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final MeterClient meter;
    private final BillingAccountRepository accounts;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final PaymentRepository payments;
    private final BillingDocumentRepository documents;
    private final TenantRepository tenants;
    private final StorageBrowserService storage;
    private final UserNameResolver names;
    private final String bucket;

    public BillingService(MeterClient meter, BillingAccountRepository accounts, InvoiceRepository invoices, InvoiceLineRepository lines,
        PaymentRepository payments, BillingDocumentRepository documents, TenantRepository tenants, StorageBrowserService storage,
        UserNameResolver names, @Value(StoragePropertyDefaults.CONFIG_BUCKET) String bucket) {
        this.meter = meter; this.accounts = accounts; this.invoices = invoices; this.lines = lines; this.payments = payments;
        this.documents = documents; this.tenants = tenants; this.storage = storage; this.names = names; this.bucket = bucket;
    }

    // ---- accounts --------------------------------------------------------------------------

    public BillingAccount accountFor(Long tenantId) {
        return this.accounts.findByTenantId(tenantId).orElseGet(() -> {
            BillingAccount a = new BillingAccount();
            a.setTenantId(tenantId);
            a.setLegalName(this.tenants.findById(tenantId).map(Tenant::getTenantName).orElse("Workspace " + tenantId));
            a.setCurrency("USD"); a.setPaymentTermsDays(30); a.setTaxRatePercent(BigDecimal.ZERO); a.setStatus("Active");
            return a;
        });
    }

    @Transactional
    public BillingAccount saveAccount(Long tenantId, BillingAccount changes) {
        BillingAccount a = this.accountFor(tenantId);
        a.setLegalName(changes.getLegalName()); a.setAddress(changes.getAddress()); a.setBillingEmail(changes.getBillingEmail());
        a.setTaxId(blankToNull(changes.getTaxId())); a.setTaxLabel(blankToNull(changes.getTaxLabel()));
        a.setTaxRatePercent(changes.getTaxRatePercent() == null ? BigDecimal.ZERO : changes.getTaxRatePercent());
        a.setCurrency(changes.getCurrency() == null || changes.getCurrency().trim().isEmpty() ? "USD" : changes.getCurrency().trim().toUpperCase());
        a.setPaymentTermsDays(changes.getPaymentTermsDays() == null || changes.getPaymentTermsDays() < 0 ? 30 : changes.getPaymentTermsDays());
        if (a.getBillingAccountId() == null) { a.setDateCreated(now()); a.setCreatedBy(TenantContext.getAppUserId()); }
        a.setDateUpdated(now()); a.setUpdatedBy(TenantContext.getAppUserId());
        return this.accounts.save(a);
    }

    /** Tax applies only with a number AND a rate -- the user's rule. */
    static boolean taxApplies(BillingAccount a) {
        return a.getTaxId() != null && !a.getTaxId().trim().isEmpty() && a.getTaxRatePercent() != null && a.getTaxRatePercent().signum() > 0;
    }

    // ---- drafting and issuing --------------------------------------------------------------

    /** The month's draft, built (or rebuilt) from the meter. An issued invoice for the month is left alone. */
    @Transactional
    public Invoice draft(Long tenantId, YearMonth period) {
        LocalDate start = period.atDay(1), end = period.atEndOfMonth();
        Optional<Invoice> existingDraft = this.invoices.findFirstByTenantIdAndPeriodStartAndKindAndStatus(tenantId, start, "invoice", DRAFT);
        Invoice invoice = existingDraft.orElseGet(() -> {
            Invoice i = new Invoice();
            i.setTenantId(tenantId); i.setKind("invoice"); i.setPeriodStart(start); i.setPeriodEnd(end); i.setStatus(DRAFT);
            i.setNumber(this.nextNumber("INV", period)); i.setDateCreated(now()); i.setCreatedBy(TenantContext.getAppUserId());
            // Totalled once the lines are in; the row is saved first so the lines have an id to hang on.
            i.setSubtotal(BigDecimal.ZERO); i.setTaxRatePercent(BigDecimal.ZERO); i.setTax(BigDecimal.ZERO); i.setTotal(BigDecimal.ZERO); i.setBalance(BigDecimal.ZERO);
            return i;
        });
        BillingAccount account = this.accountFor(tenantId);
        invoice.setCurrency(account.getCurrency());
        invoice = this.invoices.save(invoice);
        // Metered lines are rebuilt; manual lines an admin added stay.
        List<InvoiceLine> kept = new ArrayList<>();
        for (InvoiceLine line : this.lines.findByInvoiceIdOrderBySortAsc(invoice.getInvoiceId())) {
            if (Boolean.TRUE.equals(line.getManual())) kept.add(line);
        }
        this.lines.deleteByInvoiceId(invoice.getInvoiceId());
        int sort = 0;
        List<InvoiceLine> fresh = new ArrayList<>();
        if (this.meter.isConfigured()) {
            Map<String, Object> usage = this.meter.usage(tenantId, start, end, "meter");
            Object rows = usage.get("rows");
            if (rows instanceof List) {
                for (Object o : (List<?>) rows) {
                    @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) o;
                    BigDecimal amount = decimal(row.get("amount"));
                    if (amount.signum() == 0 && decimal(row.get("quantity")).signum() == 0) continue;
                    InvoiceLine line = new InvoiceLine();
                    line.setInvoiceId(invoice.getInvoiceId()); line.setSort(sort++);
                    line.setMeter(String.valueOf(row.get("meter"))); line.setDescription(String.valueOf(row.get("label")));
                    line.setQuantity(decimal(row.get("quantity"))); line.setUnit(String.valueOf(row.get("unit")));
                    line.setPer(row.get("per") == null ? 1 : ((Number) row.get("per")).intValue());
                    line.setUnitPrice(decimal(row.get("unitPrice"))); line.setAmount(amount); line.setManual(false);
                    line.setIncludedQuantity(decimal(row.get("includedQuantity")));
                    line.setBillableQuantity(row.get("billableQuantity") == null ? line.getQuantity() : decimal(row.get("billableQuantity")));
                    if (row.get("tiers") instanceof List && !((List<?>) row.get("tiers")).isEmpty()) {
                        line.setPricingDetail(new Gson().toJson(row.get("tiers")));
                    }
                    fresh.add(line);
                }
            }
            // The card the meter priced this period with -- the workspace's own or the default, as
            // it stood at the start of the period. A later version never touches this bill.
            Object card = usage.get("rateCard");
            if (card instanceof Map) {
                Map<?, ?> c = (Map<?, ?>) card;
                if (c.get("version") instanceof Number) invoice.setRateCardVersion(((Number) c.get("version")).intValue());
                if (c.get("name") != null) invoice.setRateCardName(String.valueOf(c.get("name")));
            }
        }
        // Copies, not the managed rows with their ids nulled -- Hibernate refuses an identifier
        // that changed under it, and the old rows are gone with deleteByInvoiceId above.
        for (InvoiceLine old : kept) {
            InvoiceLine copy = new InvoiceLine();
            copy.setInvoiceId(invoice.getInvoiceId()); copy.setSort(sort++); copy.setMeter(old.getMeter()); copy.setDescription(old.getDescription());
            copy.setQuantity(old.getQuantity()); copy.setUnit(old.getUnit()); copy.setPer(old.getPer()); copy.setUnitPrice(old.getUnitPrice());
            copy.setAmount(old.getAmount()); copy.setPeriodLabel(old.getPeriodLabel()); copy.setManual(true);
            fresh.add(copy);
        }
        this.lines.saveAll(fresh);
        this.total(invoice, fresh, account);
        invoice.setDateUpdated(now());
        return this.invoices.save(invoice);
    }

    private void total(Invoice invoice, List<InvoiceLine> invoiceLines, BillingAccount account) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (InvoiceLine line : invoiceLines) subtotal = subtotal.add(line.getAmount() == null ? BigDecimal.ZERO : line.getAmount());
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = taxApplies(account) ? account.getTaxRatePercent() : BigDecimal.ZERO;
        BigDecimal tax = subtotal.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        invoice.setSubtotal(subtotal); invoice.setTaxRatePercent(rate); invoice.setTax(tax); invoice.setTotal(subtotal.add(tax));
        invoice.setBalance(invoice.getTotal().subtract(this.paidOn(invoice)));
    }

    private BigDecimal paidOn(Invoice invoice) {
        if (invoice.getInvoiceId() == null) return BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(invoice.getInvoiceId())) {
            if ("verified".equals(p.getStatus())) paid = paid.add(p.getAmount());
        }
        return paid;
    }

    @Transactional
    public InvoiceLine addManualLine(Long invoiceId, String description, BigDecimal quantity, BigDecimal unitPrice) {
        Invoice invoice = this.mustBeDraft(invoiceId);
        InvoiceLine line = new InvoiceLine();
        line.setInvoiceId(invoiceId); line.setDescription(description); line.setQuantity(quantity); line.setUnit("each"); line.setPer(1);
        line.setUnitPrice(unitPrice); line.setAmount(quantity.multiply(unitPrice).setScale(5, RoundingMode.HALF_UP)); line.setManual(true);
        line.setSort((int) this.lines.findByInvoiceIdOrderBySortAsc(invoiceId).size());
        line = this.lines.save(line);
        this.total(invoice, this.lines.findByInvoiceIdOrderBySortAsc(invoiceId), this.accountFor(invoice.getTenantId()));
        this.invoices.save(invoice);
        return line;
    }

    @Transactional
    public Invoice issue(Long invoiceId) throws IOException {
        Invoice invoice = this.mustBeDraft(invoiceId);
        BillingAccount account = this.accountFor(invoice.getTenantId());
        List<InvoiceLine> invoiceLines = this.lines.findByInvoiceIdOrderBySortAsc(invoiceId);
        this.total(invoice, invoiceLines, account);
        invoice.setStatus(invoice.getTotal().signum() == 0 ? PAID : ISSUED);
        invoice.setIssuedAt(now());
        invoice.setDueAt(Timestamp.valueOf(invoice.getIssuedAt().toLocalDateTime().plusDays(account.getPaymentTermsDays() == null ? 30 : account.getPaymentTermsDays())));
        if (PAID.equals(invoice.getStatus())) invoice.setPaidAt(invoice.getIssuedAt());
        invoice.setDateUpdated(now()); invoice.setUpdatedBy(TenantContext.getAppUserId());
        invoice = this.invoices.save(invoice);
        byte[] pdf = BillingPdf.render(this.invoiceDoc(invoice, invoiceLines, account));
        BillingDocument doc = this.store(invoice.getTenantId(), invoice.getInvoiceId(), null, "invoice", invoice.getNumber(),
            invoice.getNumber() + ".pdf", "application/pdf", pdf, invoice.getTotal());
        invoice.setPdfObjectKey(doc.getObjectKey());
        return this.invoices.save(invoice);
    }

    @Transactional
    public Invoice voidInvoice(Long invoiceId, String reason) {
        Invoice invoice = this.find(invoiceId);
        if (VOID.equals(invoice.getStatus())) return invoice;
        if (this.paidOn(invoice).signum() > 0) throw new IllegalStateException("A partly paid invoice cannot be voided; issue a credit note for the rest.");
        invoice.setStatus(VOID); invoice.setVoidedAt(now()); invoice.setBalance(BigDecimal.ZERO);
        invoice.setNote(((invoice.getNote() == null ? "" : invoice.getNote() + " ") + "Voided: " + reason).trim());
        invoice.setDateUpdated(now()); invoice.setUpdatedBy(TenantContext.getAppUserId());
        return this.invoices.save(invoice);
    }

    /** A negative invoice that references the original and is applied to it as a payment. */
    @Transactional
    public Invoice creditNote(Long invoiceId, BigDecimal amount, String reason) throws IOException {
        Invoice original = this.find(invoiceId);
        if (!Arrays.asList(ISSUED, PARTIAL, OVERDUE, PAID).contains(original.getStatus())) throw new IllegalStateException("Only an issued invoice can be credited.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A credit note needs an amount above zero.");
        BillingAccount account = this.accountFor(original.getTenantId());
        Invoice note = new Invoice();
        note.setTenantId(original.getTenantId()); note.setKind("credit_note"); note.setReferencesInvoiceId(original.getInvoiceId());
        note.setPeriodStart(original.getPeriodStart()); note.setPeriodEnd(original.getPeriodEnd()); note.setStatus(ISSUED);
        note.setNumber(this.nextNumber("CN", YearMonth.from(original.getPeriodStart()))); note.setCurrency(original.getCurrency());
        note.setSubtotal(amount.negate()); note.setTaxRatePercent(BigDecimal.ZERO); note.setTax(BigDecimal.ZERO); note.setTotal(amount.negate()); note.setBalance(BigDecimal.ZERO);
        note.setNote(reason); note.setIssuedAt(now()); note.setDateCreated(now()); note.setCreatedBy(TenantContext.getAppUserId());
        note = this.invoices.save(note);
        InvoiceLine line = new InvoiceLine();
        line.setInvoiceId(note.getInvoiceId()); line.setSort(0); line.setDescription("Credit against " + original.getNumber() + (reason == null ? "" : " - " + reason));
        line.setQuantity(BigDecimal.ONE); line.setUnit("each"); line.setPer(1); line.setUnitPrice(amount.negate()); line.setAmount(amount.negate()); line.setManual(true);
        this.lines.save(line);
        byte[] pdf = BillingPdf.render(this.invoiceDoc(note, Arrays.asList(line), account));
        BillingDocument doc = this.store(note.getTenantId(), note.getInvoiceId(), null, "credit_note", note.getNumber(), note.getNumber() + ".pdf", "application/pdf", pdf, note.getTotal());
        note.setPdfObjectKey(doc.getObjectKey());
        note = this.invoices.save(note);
        // Applied to the original as a verified payment of kind credit_note.
        Payment applied = new Payment();
        applied.setTenantId(original.getTenantId()); applied.setInvoiceId(original.getInvoiceId()); applied.setAmount(amount);
        applied.setMethod("credit_note"); applied.setReference(note.getNumber()); applied.setStatus("verified");
        applied.setVerifiedBy(TenantContext.getAppUserId()); applied.setVerifiedAt(now()); applied.setReceivedAt(now()); applied.setDateCreated(now());
        this.payments.save(applied);
        this.settle(original);
        return note;
    }

    // ---- payments --------------------------------------------------------------------------

    /** A workspace says it paid: a slip and an amount. Verified by the platform before it counts. */
    @Transactional
    public Payment submitPayment(Long invoiceId, BigDecimal amount, String method, String reference, String note, MultipartFile slip) throws IOException {
        Invoice invoice = this.find(invoiceId);
        if (!Arrays.asList(ISSUED, PARTIAL, OVERDUE).contains(invoice.getStatus())) throw new IllegalStateException("This invoice is not open for payment.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A payment needs an amount above zero.");
        Payment p = new Payment();
        p.setTenantId(invoice.getTenantId()); p.setInvoiceId(invoiceId); p.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        p.setMethod(method == null || method.trim().isEmpty() ? "bank" : method.trim()); p.setReference(reference); p.setNote(note);
        p.setStatus("submitted"); p.setSubmittedBy(TenantContext.getAppUserId()); p.setDateCreated(now());
        p = this.payments.save(p);
        if (slip != null && !slip.isEmpty()) {
            String name = slip.getOriginalFilename() == null ? "slip" : slip.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
            BillingDocument doc = this.store(invoice.getTenantId(), invoiceId, p.getPaymentId(), "payment_slip", null, name,
                slip.getContentType() == null ? "application/octet-stream" : slip.getContentType(), slip.getBytes(), p.getAmount());
            p.setSlipObjectKey(doc.getObjectKey());
            p = this.payments.save(p);
        }
        return p;
    }

    /** The platform confirms the money arrived: the balance moves and a receipt is issued. */
    @Transactional
    public Payment verifyPayment(Long paymentId, boolean accept, String note) throws IOException {
        Payment p = this.payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("No such payment."));
        if (!"submitted".equals(p.getStatus())) throw new IllegalStateException("This payment was already " + p.getStatus() + ".");
        Invoice invoice = this.find(p.getInvoiceId());
        p.setStatus(accept ? "verified" : "rejected"); p.setVerifiedBy(TenantContext.getAppUserId()); p.setVerifiedAt(now());
        if (note != null && !note.trim().isEmpty()) p.setNote(((p.getNote() == null ? "" : p.getNote() + " ") + note).trim());
        if (accept) {
            p.setReceivedAt(p.getReceivedAt() == null ? now() : p.getReceivedAt());
            p.setReceiptNumber(this.nextNumber("RCP", YearMonth.now()));
            p = this.payments.save(p);
            BillingAccount account = this.accountFor(invoice.getTenantId());
            byte[] pdf = BillingPdf.render(this.receiptDoc(p, invoice, account));
            BillingDocument doc = this.store(invoice.getTenantId(), invoice.getInvoiceId(), p.getPaymentId(), "receipt", p.getReceiptNumber(),
                p.getReceiptNumber() + ".pdf", "application/pdf", pdf, p.getAmount());
            logger.info("Receipt {} issued for {} against {}", p.getReceiptNumber(), p.getAmount(), invoice.getNumber());
            this.settle(invoice);
            return p;
        }
        return this.payments.save(p);
    }

    private void settle(Invoice invoice) {
        BigDecimal paid = this.paidOn(invoice);
        invoice.setBalance(invoice.getTotal().subtract(paid).max(BigDecimal.ZERO));
        if (invoice.getBalance().signum() == 0) { invoice.setStatus(PAID); invoice.setPaidAt(now()); }
        else if (paid.signum() > 0) invoice.setStatus(PARTIAL);
        else if (invoice.getDueAt() != null && invoice.getDueAt().before(now())) invoice.setStatus(OVERDUE);
        else invoice.setStatus(ISSUED);
        invoice.setDateUpdated(now());
        this.invoices.save(invoice);
    }

    /** Issued invoices past their due date become overdue. Run daily; harmless twice. */
    @Transactional
    public int markOverdue() {
        int marked = 0;
        for (Invoice invoice : this.invoices.findByStatusIn(Arrays.asList(ISSUED, PARTIAL))) {
            if (invoice.getDueAt() != null && invoice.getDueAt().before(now()) && invoice.getBalance().signum() > 0) {
                invoice.setStatus(OVERDUE); invoice.setDateUpdated(now()); this.invoices.save(invoice); marked++;
            }
        }
        return marked;
    }

    // ---- documents -------------------------------------------------------------------------

    private BillingDocument store(Long tenantId, Long invoiceId, Long paymentId, String kind, String number, String fileName,
        String contentType, byte[] bytes, BigDecimal amount) {
        String key = String.format("billing/%d/%s/%s/%s", tenantId, YearMonth.now().getYear(), kind, System.currentTimeMillis() + "-" + fileName);
        this.storage.uploadForWorkflow(this.bucket, key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        BillingDocument doc = new BillingDocument();
        doc.setTenantId(tenantId); doc.setInvoiceId(invoiceId); doc.setPaymentId(paymentId); doc.setKind(kind); doc.setNumber(number);
        doc.setFileName(fileName); doc.setContentType(contentType); doc.setSizeBytes((long) bytes.length); doc.setObjectKey(key);
        doc.setAmount(amount); doc.setIssuedAt(now()); doc.setCreatedBy(TenantContext.getAppUserId());
        return this.documents.save(doc);
    }

    public BillingDocument document(Long documentId) {
        return this.documents.findById(documentId).orElseThrow(() -> new IllegalArgumentException("No such document."));
    }

    public ObjectContentDto bytesOf(BillingDocument doc) {
        return this.storage.readForWorkflow(this.bucket, doc.getObjectKey());
    }

    /** Payment slips awaiting the platform's verification, for one workspace or all. */
    public List<Payment> pendingPayments(Long tenantId) {
        List<Payment> out = new ArrayList<>();
        for (Payment p : this.payments.findByStatusOrderByDateCreatedAsc("submitted")) {
            if (tenantId == null || tenantId.equals(p.getTenantId())) out.add(p);
        }
        return out;
    }

    public List<BillingDocument> documentsFor(Long tenantId) {
        return tenantId == null ? this.documents.findAllByOrderByIssuedAtDesc() : this.documents.findByTenantIdOrderByIssuedAtDesc(tenantId);
    }

    /** A statement: every invoice, credit note and payment in a range, and the balance at the end. */
    @Transactional
    public BillingDocument statement(Long tenantId, LocalDate from, LocalDate to) throws IOException {
        BillingAccount account = this.accountFor(tenantId);
        List<Invoice> all = this.invoices.findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(tenantId);
        BillingPdf.Doc doc = new BillingPdf.Doc();
        doc.title = "Statement"; doc.number = "STM-" + tenantId + "-" + from + "-" + to; doc.currency = account.getCurrency();
        doc.billedTo = this.billedTo(account); doc.taxId = account.getTaxId();
        doc.facts = new ArrayList<>(); doc.facts.add(new String[] {"Period", DAY.format(from) + " - " + DAY.format(to)}); doc.facts.add(new String[] {"Prepared", DAY.format(LocalDate.now())});
        doc.lines = new ArrayList<>();
        BigDecimal invoiced = BigDecimal.ZERO, paid = BigDecimal.ZERO, balance = BigDecimal.ZERO;
        for (int i = all.size() - 1; i >= 0; i--) {
            Invoice inv = all.get(i);
            if (VOID.equals(inv.getStatus()) || DRAFT.equals(inv.getStatus()) || inv.getIssuedAt() == null) continue;
            LocalDate day = inv.getIssuedAt().toLocalDateTime().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            doc.lines.add(new BillingPdf.Line(DAY.format(day) + "  " + ("credit_note".equals(inv.getKind()) ? "Credit note " : "Invoice ") + inv.getNumber(), "", "", BillingPdf.money(inv.getTotal(), null)));
            invoiced = invoiced.add(inv.getTotal());
            for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(inv.getInvoiceId())) {
                if (!"verified".equals(p.getStatus()) || "credit_note".equals(p.getMethod())) continue;
                doc.lines.add(new BillingPdf.Line("    Payment " + (p.getReference() == null ? "" : p.getReference()) + " (" + p.getMethod() + ")", "", "", BillingPdf.money(p.getAmount().negate(), null)));
                paid = paid.add(p.getAmount());
            }
            balance = balance.add(inv.getBalance() == null ? BigDecimal.ZERO : inv.getBalance());
        }
        doc.totals = new ArrayList<>();
        doc.totals.add(new String[] {"Invoiced", BillingPdf.money(invoiced, doc.currency)});
        doc.totals.add(new String[] {"Paid", BillingPdf.money(paid, doc.currency)});
        doc.totals.add(new String[] {"Balance open", BillingPdf.money(balance, doc.currency)});
        byte[] pdf = BillingPdf.render(doc);
        return this.store(tenantId, null, null, "statement", doc.number, doc.number + ".pdf", "application/pdf", pdf, balance);
    }

    // ---- reads -----------------------------------------------------------------------------

    public List<Invoice> invoicesFor(Long tenantId) {
        return tenantId == null ? this.invoices.findAllByOrderByPeriodStartDescInvoiceIdDesc() : this.invoices.findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(tenantId);
    }

    public Invoice find(Long invoiceId) {
        return this.invoices.findById(invoiceId).orElseThrow(() -> new IllegalArgumentException("No such invoice."));
    }

    public Optional<Invoice> byNumber(String number) { return this.invoices.findByNumber(number); }
    public List<InvoiceLine> linesOf(Long invoiceId) { return this.lines.findByInvoiceIdOrderBySortAsc(invoiceId); }
    public List<Payment> paymentsOf(Long invoiceId) { return this.payments.findByInvoiceIdOrderByDateCreatedAsc(invoiceId); }
    public List<BillingDocument> documentsOf(Long invoiceId) { return this.documents.findByInvoiceIdOrderByIssuedAtAsc(invoiceId); }

    public Map<Long, String> tenantNames() {
        Map<Long, String> out = new HashMap<>();
        for (Tenant t : this.tenants.findAll()) out.put(t.getTenantId(), t.getTenantName());
        return out;
    }

    public Map<Long, String> userNames(Set<Long> ids) {
        return ids.isEmpty() ? new HashMap<>() : this.names.namesFor(ids);
    }

    /** What the platform sees: invoiced, collected, open, overdue per month and per workspace. */
    public Map<String, Object> analytics(LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> byMonth = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byTenant = new LinkedHashMap<>();
        Map<Long, String> tenantNames = this.tenantNames();
        BigDecimal invoiced = BigDecimal.ZERO, collected = BigDecimal.ZERO, open = BigDecimal.ZERO, overdue = BigDecimal.ZERO, drafts = BigDecimal.ZERO;
        int overdueCount = 0;
        List<long[]> daysToPay = new ArrayList<>();
        for (Invoice inv : this.invoices.findByPeriodStartBetweenOrderByTenantIdAscPeriodStartAsc(from.withDayOfMonth(1), to)) {
            String month = YearMonth.from(inv.getPeriodStart()).toString();
            Map<String, BigDecimal> m = byMonth.computeIfAbsent(month, k -> new LinkedHashMap<>());
            Map<String, Object> t = byTenant.computeIfAbsent(inv.getTenantId(), k -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tenantId", k); row.put("tenantName", tenantNames.getOrDefault(k, "Workspace " + k));
                row.put("invoiced", BigDecimal.ZERO); row.put("collected", BigDecimal.ZERO); row.put("open", BigDecimal.ZERO); row.put("overdue", BigDecimal.ZERO); row.put("status", "");
                return row;
            });
            if (DRAFT.equals(inv.getStatus())) { drafts = drafts.add(inv.getTotal()); m.merge("drafts", inv.getTotal(), BigDecimal::add); continue; }
            if (VOID.equals(inv.getStatus())) continue;
            BigDecimal total = inv.getTotal(), paidAmount = inv.getTotal().subtract(inv.getBalance() == null ? BigDecimal.ZERO : inv.getBalance());
            invoiced = invoiced.add(total); m.merge("invoiced", total, BigDecimal::add); t.put("invoiced", ((BigDecimal) t.get("invoiced")).add(total));
            collected = collected.add(paidAmount); m.merge("collected", paidAmount, BigDecimal::add); t.put("collected", ((BigDecimal) t.get("collected")).add(paidAmount));
            if (inv.getBalance() != null && inv.getBalance().signum() > 0) {
                open = open.add(inv.getBalance()); m.merge("open", inv.getBalance(), BigDecimal::add); t.put("open", ((BigDecimal) t.get("open")).add(inv.getBalance()));
                if (OVERDUE.equals(inv.getStatus())) { overdue = overdue.add(inv.getBalance()); overdueCount++; t.put("overdue", ((BigDecimal) t.get("overdue")).add(inv.getBalance())); t.put("status", OVERDUE); }
                else if (!OVERDUE.equals(t.get("status"))) t.put("status", inv.getStatus());
            } else if (!"".equals(t.get("status")) && !OVERDUE.equals(t.get("status"))) {
                t.put("status", PAID);
            } else if ("".equals(t.get("status"))) t.put("status", PAID);
            if (inv.getPaidAt() != null && inv.getIssuedAt() != null) daysToPay.add(new long[] {(inv.getPaidAt().getTime() - inv.getIssuedAt().getTime()) / 86_400_000L});
        }
        List<Map<String, Object>> months = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> e : byMonth.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("month", e.getKey()); row.putAll(e.getValue()); months.add(row);
        }
        long median = 0;
        if (!daysToPay.isEmpty()) { daysToPay.sort((a, b) -> Long.compare(a[0], b[0])); median = daysToPay.get(daysToPay.size() / 2)[0]; }
        out.put("invoiced", invoiced); out.put("collected", collected); out.put("open", open); out.put("overdue", overdue); out.put("overdueCount", overdueCount);
        out.put("drafts", drafts); out.put("medianDaysToPay", median); out.put("months", months); out.put("tenants", new ArrayList<>(byTenant.values()));
        out.put("pendingPayments", this.payments.findByStatusOrderByDateCreatedAsc("submitted").size());
        return out;
    }

    // ---- pdf content -----------------------------------------------------------------------

    private String billedTo(BillingAccount account) {
        StringBuilder b = new StringBuilder(account.getLegalName() == null ? "" : account.getLegalName());
        if (account.getAddress() != null && !account.getAddress().isEmpty()) for (String l : account.getAddress().split("\n")) b.append('\n').append(l);
        if (account.getBillingEmail() != null) b.append('\n').append(account.getBillingEmail());
        return b.toString();
    }

    BillingPdf.Doc invoiceDoc(Invoice invoice, List<InvoiceLine> invoiceLines, BillingAccount account) {
        BillingPdf.Doc doc = new BillingPdf.Doc();
        boolean credit = "credit_note".equals(invoice.getKind());
        doc.title = credit ? "Credit note" : "Invoice"; doc.number = invoice.getNumber(); doc.currency = invoice.getCurrency(); doc.qrText = invoice.getNumber();
        doc.billedTo = this.billedTo(account); doc.taxId = account.getTaxId();
        doc.facts = new ArrayList<>();
        doc.facts.add(new String[] {"Period", DAY.format(invoice.getPeriodStart()) + " - " + DAY.format(invoice.getPeriodEnd())});
        if (invoice.getIssuedAt() != null) doc.facts.add(new String[] {"Issued", DAY.format(invoice.getIssuedAt().toLocalDateTime().toLocalDate())});
        if (invoice.getDueAt() != null) doc.facts.add(new String[] {"Due", DAY.format(invoice.getDueAt().toLocalDateTime().toLocalDate()) + " (net " + account.getPaymentTermsDays() + ")"});
        if (credit && invoice.getReferencesInvoiceId() != null) this.invoices.findById(invoice.getReferencesInvoiceId()).ifPresent(o -> doc.facts.add(new String[] {"Against", o.getNumber()}));
        if (invoice.getRateCardVersion() != null) {
            doc.facts.add(new String[] {"Rate card", (invoice.getRateCardName() == null ? "" : invoice.getRateCardName() + " ") + "v" + invoice.getRateCardVersion()});
        }
        doc.lines = new ArrayList<>();
        for (InvoiceLine l : invoiceLines) {
            doc.lines.add(new BillingPdf.Line(l.getDescription() + (l.getPeriodLabel() == null ? "" : " (" + l.getPeriodLabel() + ")"),
                BillingPdf.quantity(l.getQuantity(), l.getUnit()), BillingPdf.rate(l.getUnitPrice(), l.getPer(), l.getUnit()), BillingPdf.money(l.getAmount(), null)));
            // What the calculation applied, under the line: the allowance and each tier band.
            if (l.getIncludedQuantity() != null && l.getIncludedQuantity().signum() > 0) {
                doc.lines.add(new BillingPdf.Line("    includes " + BillingPdf.quantity(l.getIncludedQuantity(), l.getUnit()) + " at no charge; "
                    + BillingPdf.quantity(l.getBillableQuantity(), l.getUnit()) + " billable", "", "", ""));
            }
            for (Map<String, Object> band : tierBands(l)) {
                doc.lines.add(new BillingPdf.Line("    " + BillingPdf.quantity(decimal(band.get("units")), l.getUnit()) + " from "
                    + BillingPdf.quantity(decimal(band.get("from")), l.getUnit()) + (band.get("to") == null ? " up" : " to " + BillingPdf.quantity(decimal(band.get("to")), l.getUnit())),
                    "", BillingPdf.rate(decimal(band.get("unit_price")), l.getPer(), l.getUnit()), ""));
            }
        }
        doc.totals = new ArrayList<>();
        doc.totals.add(new String[] {"Subtotal", BillingPdf.money(invoice.getSubtotal(), doc.currency)});
        if (invoice.getTaxRatePercent() != null && invoice.getTaxRatePercent().signum() > 0) {
            doc.totals.add(new String[] {(account.getTaxLabel() == null ? "Tax" : account.getTaxLabel()) + " " + invoice.getTaxRatePercent().stripTrailingZeros().toPlainString() + " %", BillingPdf.money(invoice.getTax(), doc.currency)});
        } else if (!credit) {
            doc.totals.add(new String[] {"Tax", "not applied"});
        }
        doc.totals.add(new String[] {"Total", BillingPdf.money(invoice.getTotal(), doc.currency)});
        doc.note = invoice.getNote();
        return doc;
    }

    /** The tier bands a frozen line carries, as the meter reported them; none for a flat price. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> tierBands(InvoiceLine line) {
        if (line.getPricingDetail() == null || line.getPricingDetail().isEmpty()) return new ArrayList<>();
        try {
            return new Gson().fromJson(line.getPricingDetail(), List.class);
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    BillingPdf.Doc receiptDoc(Payment p, Invoice invoice, BillingAccount account) {
        BillingPdf.Doc doc = new BillingPdf.Doc();
        doc.title = "Receipt"; doc.number = p.getReceiptNumber(); doc.currency = invoice.getCurrency(); doc.qrText = p.getReceiptNumber();
        doc.billedTo = this.billedTo(account); doc.taxId = account.getTaxId();
        doc.facts = new ArrayList<>();
        doc.facts.add(new String[] {"Received", DAY.format(p.getReceivedAt().toLocalDateTime().toLocalDate())});
        doc.facts.add(new String[] {"Against", invoice.getNumber()});
        doc.facts.add(new String[] {"Method", p.getMethod() + (p.getReference() == null ? "" : " " + p.getReference())});
        doc.lines = new ArrayList<>();
        doc.lines.add(new BillingPdf.Line("Payment received against " + invoice.getNumber(), "", "", BillingPdf.money(p.getAmount(), null)));
        doc.totals = new ArrayList<>();
        doc.totals.add(new String[] {"Received", BillingPdf.money(p.getAmount(), doc.currency)});
        BigDecimal remaining = invoice.getTotal().subtract(this.paidOn(invoice)).max(BigDecimal.ZERO);
        doc.totals.add(new String[] {"Balance remaining", BillingPdf.money(remaining, doc.currency)});
        doc.note = p.getNote();
        return doc;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Invoice mustBeDraft(Long invoiceId) {
        Invoice invoice = this.find(invoiceId);
        if (!DRAFT.equals(invoice.getStatus())) throw new IllegalStateException("Only a draft can be changed; this invoice is " + invoice.getStatus() + ".");
        return invoice;
    }

    private String nextNumber(String prefix, YearMonth period) {
        String base = prefix + "-" + period;
        long n = this.invoices.countByNumberPrefix(base + "-") + 1;
        String candidate;
        do { candidate = String.format("%s-%04d", base, n++); } while (this.invoices.findByNumber(candidate).isPresent() && !"RCP".equals(prefix));
        if ("RCP".equals(prefix)) candidate = String.format("%s-%04d", base, this.payments.findAll().size() + 1);
        return candidate;
    }

    static BigDecimal decimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try { return new BigDecimal(String.valueOf(v)); } catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private static Timestamp now() { return new Timestamp(System.currentTimeMillis()); }
    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
