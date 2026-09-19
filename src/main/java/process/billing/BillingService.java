package process.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import process.config.StoragePropertyDefaults;
import process.model.dto.ObjectContentDto;
import process.model.enums.BillingDocumentKind;
import process.model.enums.InvoiceKind;
import process.model.enums.InvoiceStatus;
import process.model.enums.PaymentMethod;
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
        if (tenantId == null || !this.tenants.existsById(tenantId)) throw new IllegalArgumentException("No such workspace.");
        LocalDate start = period.atDay(1), end = period.atEndOfMonth();
        Optional<Invoice> existingDraft = this.invoices.findFirstByTenantIdAndPeriodStartAndKindAndStatus(tenantId, start, InvoiceKind.INVOICE.value(), InvoiceStatus.DRAFT.value());
        Invoice invoice = existingDraft.orElseGet(() -> {
            Invoice i = new Invoice();
            i.setTenantId(tenantId); i.setKind(InvoiceKind.INVOICE.value()); i.setPeriodStart(start); i.setPeriodEnd(end); i.setStatus(InvoiceStatus.DRAFT.value());
            i.setNumber(this.nextNumber(InvoiceKind.INVOICE.numberPrefix(), period)); i.setDateCreated(now()); i.setCreatedBy(TenantContext.getAppUserId());
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

    // ---- what a request may carry: the columns' widths and the sums that make sense -----------

    static final int DESCRIPTION_MAX = 300;
    static final int NOTE_MAX = 600;
    static final int REFERENCE_MAX = 120;
    /** A manual line's unit price, either sign (a discount is a negative line); beyond this is a typo. */
    static final BigDecimal UNIT_PRICE_MAX = new BigDecimal("1000000");
    static final BigDecimal QUANTITY_MAX = new BigDecimal("1000000000");
    /** A payment slip: a PDF or a picture of the transfer, up to this many bytes. */
    static final long SLIP_MAX_BYTES = 10L * 1024 * 1024;

    static String text(String value, String what, int max, boolean required) {
        String v = value == null ? "" : value.trim();
        if (required && v.isEmpty()) throw new IllegalArgumentException(what + " is required.");
        if (v.length() > max) throw new IllegalArgumentException(what + " can be at most " + max + " characters.");
        return v.isEmpty() ? null : v;
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

    /** Verified payments that were money -- a credit note applied is not collected. */
    private BigDecimal moneyPaidOn(Invoice invoice) {
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(invoice.getInvoiceId())) {
            if (PaymentStatus.VERIFIED.is(p.getStatus()) && !PaymentMethod.CREDIT_NOTE.is(p.getMethod())) paid = paid.add(p.getAmount());
        }
        return paid;
    }

    /** What credit notes already took off an invoice: the sum of their totals, as a positive figure. */
    private BigDecimal creditedOn(Invoice invoice) {
        BigDecimal credited = BigDecimal.ZERO;
        for (Invoice n : this.invoices.findByReferencesInvoiceId(invoice.getInvoiceId())) {
            if (InvoiceKind.CREDIT_NOTE.is(n.getKind()) && !InvoiceStatus.VOID.is(n.getStatus())) credited = credited.add(n.getTotal().negate());
        }
        return credited;
    }

    private BigDecimal paidOn(Invoice invoice) {
        if (invoice.getInvoiceId() == null) return BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(invoice.getInvoiceId())) {
            if (PaymentStatus.VERIFIED.is(p.getStatus())) paid = paid.add(p.getAmount());
        }
        return paid;
    }

    @Transactional
    public InvoiceLine addManualLine(Long invoiceId, String description, BigDecimal quantity, BigDecimal unitPrice) {
        Invoice invoice = this.mustBeDraft(invoiceId);
        String what = text(description, "A description", DESCRIPTION_MAX, true);
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(QUANTITY_MAX) > 0) throw new IllegalArgumentException("A quantity above zero.");
        if (unitPrice == null || unitPrice.abs().compareTo(UNIT_PRICE_MAX) > 0) throw new IllegalArgumentException("A unit price up to " + UNIT_PRICE_MAX.toPlainString() + " either way.");
        InvoiceLine line = new InvoiceLine();
        line.setInvoiceId(invoiceId); line.setDescription(what); line.setQuantity(quantity); line.setUnit("each"); line.setPer(1);
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
        invoice.setStatus((invoice.getTotal().signum() == 0 ? InvoiceStatus.PAID : InvoiceStatus.ISSUED).value());
        invoice.setIssuedAt(now());
        invoice.setDueAt(Timestamp.valueOf(invoice.getIssuedAt().toLocalDateTime().plusDays(account.getPaymentTermsDays() == null ? 30 : account.getPaymentTermsDays())));
        if (InvoiceStatus.PAID.is(invoice.getStatus())) invoice.setPaidAt(invoice.getIssuedAt());
        invoice.setDateUpdated(now()); invoice.setUpdatedBy(TenantContext.getAppUserId());
        invoice = this.invoices.save(invoice);
        byte[] pdf = BillingPdf.render(this.invoiceDoc(invoice, invoiceLines, account));
        BillingDocument doc = this.store(invoice.getTenantId(), invoice.getInvoiceId(), null, BillingDocumentKind.INVOICE, invoice.getNumber(),
            invoice.getNumber() + ".pdf", "application/pdf", pdf, invoice.getTotal());
        invoice.setPdfObjectKey(doc.getObjectKey());
        return this.invoices.save(invoice);
    }

    @Transactional
    public Invoice voidInvoice(Long invoiceId, String reason) {
        Invoice invoice = this.find(invoiceId);
        if (InvoiceStatus.VOID.is(invoice.getStatus())) return invoice;
        String why = text(reason, "A reason", NOTE_MAX / 2, true);
        if (this.paidOn(invoice).signum() > 0) throw new IllegalStateException("A partly paid invoice cannot be voided; issue a credit note for the rest.");
        invoice.setStatus(InvoiceStatus.VOID.value()); invoice.setVoidedAt(now()); invoice.setBalance(BigDecimal.ZERO);
        invoice.setNote(text(((invoice.getNote() == null ? "" : invoice.getNote() + " ") + "Voided: " + why).trim(), "The note", NOTE_MAX, false));
        invoice.setDateUpdated(now()); invoice.setUpdatedBy(TenantContext.getAppUserId());
        return this.invoices.save(invoice);
    }

    /** A negative invoice that references the original and is applied to it as a payment. */
    @Transactional
    public Invoice creditNote(Long invoiceId, BigDecimal amount, String reason) throws IOException {
        Invoice original = this.find(invoiceId);
        if (!InvoiceKind.INVOICE.is(original.getKind()) || !InvoiceStatus.of(original.getStatus()).isCreditable()) throw new IllegalStateException("Only an issued invoice can be credited.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A credit note needs an amount above zero.");
        String why = text(reason, "A reason", NOTE_MAX, true);
        // On an open bill a credit reduces what is owed, so it cannot exceed the balance; on a
        // paid bill it records a refund owed, up to what was billed and not yet credited.
        boolean open = InvoiceStatus.of(original.getStatus()).isOpen();
        BigDecimal creditable = (open ? original.getBalance() : original.getTotal().subtract(this.creditedOn(original))).setScale(2, RoundingMode.HALF_UP);
        if (amount.setScale(2, RoundingMode.HALF_UP).compareTo(creditable) > 0) {
            throw new IllegalArgumentException("At most " + creditable.toPlainString() + " can still be credited against " + original.getNumber() + ".");
        }
        BillingAccount account = this.accountFor(original.getTenantId());
        Invoice note = new Invoice();
        note.setTenantId(original.getTenantId()); note.setKind(InvoiceKind.CREDIT_NOTE.value()); note.setReferencesInvoiceId(original.getInvoiceId());
        note.setPeriodStart(original.getPeriodStart()); note.setPeriodEnd(original.getPeriodEnd()); note.setStatus(InvoiceStatus.ISSUED.value());
        note.setNumber(this.nextNumber(InvoiceKind.CREDIT_NOTE.numberPrefix(), YearMonth.from(original.getPeriodStart()))); note.setCurrency(original.getCurrency());
        note.setSubtotal(amount.negate()); note.setTaxRatePercent(BigDecimal.ZERO); note.setTax(BigDecimal.ZERO); note.setTotal(amount.negate()); note.setBalance(BigDecimal.ZERO);
        note.setNote(why); note.setIssuedAt(now()); note.setDateCreated(now()); note.setCreatedBy(TenantContext.getAppUserId());
        note = this.invoices.save(note);
        InvoiceLine line = new InvoiceLine();
        line.setInvoiceId(note.getInvoiceId()); line.setSort(0); line.setDescription("Credit against " + original.getNumber() + (reason == null ? "" : " - " + reason));
        line.setQuantity(BigDecimal.ONE); line.setUnit("each"); line.setPer(1); line.setUnitPrice(amount.negate()); line.setAmount(amount.negate()); line.setManual(true);
        this.lines.save(line);
        byte[] pdf = BillingPdf.render(this.invoiceDoc(note, Arrays.asList(line), account));
        BillingDocument doc = this.store(note.getTenantId(), note.getInvoiceId(), null, BillingDocumentKind.CREDIT_NOTE, note.getNumber(), note.getNumber() + ".pdf", "application/pdf", pdf, note.getTotal());
        note.setPdfObjectKey(doc.getObjectKey());
        note = this.invoices.save(note);
        // Applied to the original as a verified payment of kind credit_note -- when there is a
        // balance to apply it to. Against a paid bill the note stands on its own as a refund owed.
        if (open) {
            Payment applied = new Payment();
            applied.setTenantId(original.getTenantId()); applied.setInvoiceId(original.getInvoiceId()); applied.setAmount(amount);
            applied.setMethod(PaymentMethod.CREDIT_NOTE.value()); applied.setReference(note.getNumber()); applied.setStatus(PaymentStatus.VERIFIED.value());
            applied.setVerifiedBy(TenantContext.getAppUserId()); applied.setVerifiedAt(now()); applied.setReceivedAt(now()); applied.setDateCreated(now());
            this.payments.save(applied);
            this.settle(original);
        }
        return note;
    }

    // ---- payments --------------------------------------------------------------------------

    /** A workspace says it paid: a slip and an amount. Verified by the platform before it counts. */
    @Transactional
    public Payment submitPayment(Long invoiceId, BigDecimal amount, String method, String reference, String note, MultipartFile slip) throws IOException {
        Invoice invoice = this.find(invoiceId);
        if (!InvoiceKind.INVOICE.is(invoice.getKind()) || !InvoiceStatus.of(invoice.getStatus()).isOpen()) throw new IllegalStateException("This invoice is not open for payment.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A payment needs an amount above zero.");
        BigDecimal paying = amount.setScale(2, RoundingMode.HALF_UP);
        // Slips already submitted and not yet verified count against the balance too, so two
        // slips cannot together say more was paid than is owed.
        BigDecimal open = invoice.getBalance().subtract(this.submittedOn(invoice)).setScale(2, RoundingMode.HALF_UP);
        if (paying.compareTo(open) > 0) throw new IllegalArgumentException("At most " + open.max(BigDecimal.ZERO).toPlainString() + " is still owed on " + invoice.getNumber() + ".");
        PaymentMethod how = method == null || method.trim().isEmpty() ? PaymentMethod.BANK : PaymentMethod.chosen(method.trim().toLowerCase());
        if (how == null) throw new IllegalArgumentException("The payment method must be one of " + PaymentMethod.chosenList() + ".");
        SlipContent content = slip == null || slip.isEmpty() ? null : SlipContent.of(slip);
        Payment p = new Payment();
        p.setTenantId(invoice.getTenantId()); p.setInvoiceId(invoiceId); p.setAmount(paying);
        p.setMethod(how.value()); p.setReference(text(reference, "The reference", REFERENCE_MAX, false)); p.setNote(text(note, "The note", NOTE_MAX, false));
        p.setStatus(PaymentStatus.SUBMITTED.value()); p.setSubmittedBy(TenantContext.getAppUserId()); p.setDateCreated(now());
        p = this.payments.save(p);
        if (content != null) {
            BillingDocument doc = this.store(invoice.getTenantId(), invoiceId, p.getPaymentId(), BillingDocumentKind.PAYMENT_SLIP, null, content.fileName,
                content.contentType, content.bytes, p.getAmount());
            p.setSlipObjectKey(doc.getObjectKey());
            p = this.payments.save(p);
        }
        return p;
    }

    /** Payments submitted and awaiting verification -- promised, not yet counted. */
    private BigDecimal submittedOn(Invoice invoice) {
        BigDecimal promised = BigDecimal.ZERO;
        for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(invoice.getInvoiceId())) {
            if (PaymentStatus.SUBMITTED.is(p.getStatus())) promised = promised.add(p.getAmount());
        }
        return promised;
    }

    /**
     * A payment slip as the platform will keep it: the bytes say what it is (a PDF, a PNG, a
     * JPEG), never the name or the type the browser sent; the name is reduced to a safe one.
     */
    static final class SlipContent {
        final String fileName; final String contentType; final byte[] bytes;
        private SlipContent(String fileName, String contentType, byte[] bytes) { this.fileName = fileName; this.contentType = contentType; this.bytes = bytes; }

        static SlipContent of(MultipartFile slip) throws IOException {
            if (slip.getSize() > SLIP_MAX_BYTES) throw new IllegalArgumentException("A slip can be at most " + (SLIP_MAX_BYTES / 1024 / 1024) + " MB.");
            byte[] bytes = slip.getBytes();
            String type = sniff(bytes);
            if (type == null) throw new IllegalArgumentException("A slip must be a PDF, a PNG or a JPEG.");
            String extension = "application/pdf".equals(type) ? ".pdf" : "image/png".equals(type) ? ".png" : ".jpg";
            String base = slip.getOriginalFilename() == null ? "slip" : slip.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[._]+", "");
            base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
            if (base.isEmpty()) base = "slip";
            if (base.length() > 80) base = base.substring(0, 80);
            return new SlipContent(base + extension, type, bytes);
        }

        /** The type from the first bytes: PDF, PNG or JPEG, else nothing. */
        static String sniff(byte[] b) {
            if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
            return null;
        }
    }

    /** The platform confirms the money arrived: the balance moves and a receipt is issued. */
    @Transactional
    public Payment verifyPayment(Long paymentId, boolean accept, String note) throws IOException {
        Payment p = this.payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("No such payment."));
        if (!PaymentStatus.SUBMITTED.is(p.getStatus())) throw new IllegalStateException("This payment was already " + p.getStatus() + ".");
        Invoice invoice = this.find(p.getInvoiceId());
        p.setStatus((accept ? PaymentStatus.VERIFIED : PaymentStatus.REJECTED).value()); p.setVerifiedBy(TenantContext.getAppUserId()); p.setVerifiedAt(now());
        String remark = text(note, "The note", NOTE_MAX / 2, false);
        if (remark != null) p.setNote(text(((p.getNote() == null ? "" : p.getNote() + " ") + remark).trim(), "The note", NOTE_MAX, false));
        if (accept) {
            p.setReceivedAt(p.getReceivedAt() == null ? now() : p.getReceivedAt());
            p.setReceiptNumber(this.nextNumber(BillingDocumentKind.RECEIPT.numberPrefix(), YearMonth.now()));
            p = this.payments.save(p);
            BillingAccount account = this.accountFor(invoice.getTenantId());
            byte[] pdf = BillingPdf.render(this.receiptDoc(p, invoice, account));
            BillingDocument doc = this.store(invoice.getTenantId(), invoice.getInvoiceId(), p.getPaymentId(), BillingDocumentKind.RECEIPT, p.getReceiptNumber(),
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
        if (invoice.getBalance().signum() == 0) { invoice.setStatus(InvoiceStatus.PAID.value()); invoice.setPaidAt(now()); }
        else if (paid.signum() > 0) invoice.setStatus(InvoiceStatus.PARTIALLY_PAID.value());
        else if (invoice.getDueAt() != null && invoice.getDueAt().before(now())) invoice.setStatus(InvoiceStatus.OVERDUE.value());
        else invoice.setStatus(InvoiceStatus.ISSUED.value());
        invoice.setDateUpdated(now());
        this.invoices.save(invoice);
    }

    /** Issued invoices past their due date become overdue. Run daily; harmless twice. */
    @Transactional
    public int markOverdue() {
        int marked = 0;
        for (Invoice invoice : this.invoices.findByStatusIn(InvoiceStatus.values(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID))) {
            if (invoice.getDueAt() != null && invoice.getDueAt().before(now()) && invoice.getBalance().signum() > 0) {
                invoice.setStatus(InvoiceStatus.OVERDUE.value()); invoice.setDateUpdated(now()); this.invoices.save(invoice); marked++;
            }
        }
        return marked;
    }

    // ---- documents -------------------------------------------------------------------------

    private BillingDocument store(Long tenantId, Long invoiceId, Long paymentId, BillingDocumentKind kind, String number, String fileName,
        String contentType, byte[] bytes, BigDecimal amount) {
        String key = String.format("billing/%d/%s/%s/%s", tenantId, YearMonth.now().getYear(), kind.value(), System.currentTimeMillis() + "-" + fileName);
        this.storage.uploadForWorkflow(this.bucket, key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        BillingDocument doc = new BillingDocument();
        doc.setTenantId(tenantId); doc.setInvoiceId(invoiceId); doc.setPaymentId(paymentId); doc.setKind(kind.value()); doc.setNumber(number);
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
        for (Payment p : this.payments.findByStatusOrderByDateCreatedAsc(PaymentStatus.SUBMITTED.value())) {
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
            if (InvoiceStatus.VOID.is(inv.getStatus()) || InvoiceStatus.DRAFT.is(inv.getStatus()) || inv.getIssuedAt() == null) continue;
            LocalDate day = inv.getIssuedAt().toLocalDateTime().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            doc.lines.add(new BillingPdf.Line(DAY.format(day) + "  " + (InvoiceKind.CREDIT_NOTE.is(inv.getKind()) ? "Credit note " : "Invoice ") + inv.getNumber(), "", "", BillingPdf.money(inv.getTotal(), null)));
            invoiced = invoiced.add(inv.getTotal());
            for (Payment p : this.payments.findByInvoiceIdOrderByDateCreatedAsc(inv.getInvoiceId())) {
                if (!PaymentStatus.VERIFIED.is(p.getStatus()) || PaymentMethod.CREDIT_NOTE.is(p.getMethod())) continue;
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
        return this.store(tenantId, null, null, BillingDocumentKind.STATEMENT, doc.number, doc.number + ".pdf", "application/pdf", pdf, balance);
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
    /**
     * The bill in one glance, for a profile card or a dashboard row: this month's metered cost,
     * what is owed and by when, slips waiting. One workspace, or every workspace for the platform.
     */
    public Map<String, Object> summary(Long tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate first = LocalDate.now().withDayOfMonth(1), today = LocalDate.now();
        BigDecimal monthToDate = BigDecimal.ZERO;
        String currency = tenantId == null ? "USD" : this.accountFor(tenantId).getCurrency();
        if (this.meter.isConfigured()) {
            try {
                Map<String, Object> usage = this.meter.usage(tenantId, first, today, tenantId == null ? "tenant" : "meter");
                if (usage.get("rows") instanceof List) {
                    for (Object o : (List<?>) usage.get("rows")) monthToDate = monthToDate.add(decimal(((Map<?, ?>) o).get("amount")));
                }
                Object card = usage.get("rateCard");
                if (card instanceof Map) { out.put("rateCardName", ((Map<?, ?>) card).get("name")); out.put("rateCardVersion", ((Map<?, ?>) card).get("version")); }
            } catch (RuntimeException ex) {
                logger.warn("billing summary: the meter did not answer: {}", ex.toString());
                out.put("meterDown", true);
            }
        }
        out.put("currency", currency); out.put("monthToDate", monthToDate.setScale(2, RoundingMode.HALF_UP)); out.put("periodStart", first);
        BigDecimal open = BigDecimal.ZERO, overdue = BigDecimal.ZERO;
        int openCount = 0, overdueCount = 0, pendingSlips = 0;
        Invoice nextDue = null, latest = null;
        for (Invoice inv : this.invoicesFor(tenantId)) {
            if (!InvoiceKind.INVOICE.is(inv.getKind())) continue;
            if (latest == null && !InvoiceStatus.DRAFT.is(inv.getStatus())) latest = inv;
            InvoiceStatus status = InvoiceStatus.of(inv.getStatus());
            if (!status.isOpen()) continue;
            open = open.add(inv.getBalance()); openCount++;
            if (status == InvoiceStatus.OVERDUE) { overdue = overdue.add(inv.getBalance()); overdueCount++; }
            if (inv.getDueAt() != null && (nextDue == null || inv.getDueAt().before(nextDue.getDueAt()))) nextDue = inv;
        }
        for (Payment p : this.pendingPayments(tenantId)) pendingSlips++;
        out.put("openBalance", open); out.put("openCount", openCount); out.put("overdueBalance", overdue); out.put("overdueCount", overdueCount);
        out.put("pendingSlips", pendingSlips);
        if (nextDue != null) { out.put("nextDueAt", nextDue.getDueAt()); out.put("nextDueNumber", nextDue.getNumber()); out.put("nextDueBalance", nextDue.getBalance()); }
        if (latest != null) { out.put("latestNumber", latest.getNumber()); out.put("latestTotal", latest.getTotal()); out.put("latestStatus", latest.getStatus()); out.put("latestPeriodStart", latest.getPeriodStart()); }
        return out;
    }

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
            if (InvoiceStatus.DRAFT.is(inv.getStatus())) { drafts = drafts.add(inv.getTotal()); m.merge("drafts", inv.getTotal(), BigDecimal::add); continue; }
            if (InvoiceStatus.VOID.is(inv.getStatus())) continue;
            // Collected is money that arrived: verified payments, never a credit note applied
            // (that is already inside the net invoiced figure through the note's negative total).
            BigDecimal total = inv.getTotal(), paidAmount = InvoiceKind.CREDIT_NOTE.is(inv.getKind()) ? BigDecimal.ZERO : this.moneyPaidOn(inv);
            invoiced = invoiced.add(total); m.merge("invoiced", total, BigDecimal::add); t.put("invoiced", ((BigDecimal) t.get("invoiced")).add(total));
            collected = collected.add(paidAmount); m.merge("collected", paidAmount, BigDecimal::add); t.put("collected", ((BigDecimal) t.get("collected")).add(paidAmount));
            if (inv.getBalance() != null && inv.getBalance().signum() > 0) {
                open = open.add(inv.getBalance()); m.merge("open", inv.getBalance(), BigDecimal::add); t.put("open", ((BigDecimal) t.get("open")).add(inv.getBalance()));
                if (InvoiceStatus.OVERDUE.is(inv.getStatus())) { overdue = overdue.add(inv.getBalance()); overdueCount++; t.put("overdue", ((BigDecimal) t.get("overdue")).add(inv.getBalance())); t.put("status", InvoiceStatus.OVERDUE.value()); }
                else if (!InvoiceStatus.OVERDUE.value().equals(t.get("status"))) t.put("status", inv.getStatus());
            } else if (!"".equals(t.get("status")) && !InvoiceStatus.OVERDUE.value().equals(t.get("status"))) {
                t.put("status", InvoiceStatus.PAID.value());
            } else if ("".equals(t.get("status"))) t.put("status", InvoiceStatus.PAID.value());
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
        out.put("pendingPayments", this.payments.findByStatusOrderByDateCreatedAsc(PaymentStatus.SUBMITTED.value()).size());
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
        boolean credit = InvoiceKind.CREDIT_NOTE.is(invoice.getKind());
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
        if (!InvoiceStatus.DRAFT.is(invoice.getStatus())) throw new IllegalStateException("Only a draft can be changed; this invoice is " + invoice.getStatus() + ".");
        return invoice;
    }

    private String nextNumber(String prefix, YearMonth period) {
        String base = BillingNumber.base(prefix, period);
        if (BillingDocumentKind.RECEIPT.numberPrefix().equals(prefix)) return BillingNumber.format(base, this.payments.findAll().size() + 1);
        long n = this.invoices.countByNumberPrefix(base + "-") + 1;
        String candidate;
        do { candidate = BillingNumber.format(base, (int) n++); } while (this.invoices.findByNumber(candidate).isPresent());
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
