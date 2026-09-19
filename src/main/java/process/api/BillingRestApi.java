package process.api;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import process.util.UserNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import process.billing.BillingService;
import process.billing.MeterClient;
import process.model.dto.ObjectContentDto;
import process.model.pojo.BillingAccount;
import process.model.pojo.BillingDocument;
import process.model.pojo.Invoice;
import process.model.pojo.Payment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import process.engine.cron.UsageMeasurerCron;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.time.LocalDate;

/**
 * Cost & usage: what the console reads from the meter for a workspace.
 *
 * A tenant admin reads their own workspace whatever tenantId the request names; a platform
 * admin may name any. The meter answers priced daily rows; this only decides who may ask.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/billing.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class BillingRestApi {

    private final Logger logger = LoggerFactory.getLogger(BillingRestApi.class);
    private final MeterClient meter;
    private final UsageMeasurerCron measurer;
    private final UserNameResolver names;

    private final BillingService billing;

    public BillingRestApi(MeterClient meter, UsageMeasurerCron measurer, UserNameResolver names, BillingService billing) {
        this.meter = meter; this.measurer = measurer; this.names = names; this.billing = billing;
    }

    // ---- accounts, invoices, payments, documents, analytics --------------------------------

    private ResponseEntity<?> ok(String message, Object data) { return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, message, data), HttpStatus.OK); }
    private ResponseEntity<?> refused(String message) { return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, message), HttpStatus.OK); }

    /** A tenant admin may touch their own workspace's rows only; a platform admin any. */
    private boolean mayTouch(Long tenantId) {
        return TenantContext.isPlatformAdmin() || (tenantId != null && tenantId.equals(TenantContext.getTenantId()));
    }

    private Map<String, Object> invoiceRow(Invoice i, Map<Long, String> tenantNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("invoiceId", i.getInvoiceId()); row.put("number", i.getNumber()); row.put("kind", i.getKind()); row.put("referencesInvoiceId", i.getReferencesInvoiceId());
        row.put("tenantId", i.getTenantId()); row.put("tenantName", tenantNames.get(i.getTenantId()));
        row.put("periodStart", i.getPeriodStart()); row.put("periodEnd", i.getPeriodEnd()); row.put("status", i.getStatus()); row.put("currency", i.getCurrency());
        row.put("subtotal", i.getSubtotal()); row.put("taxRatePercent", i.getTaxRatePercent()); row.put("tax", i.getTax()); row.put("total", i.getTotal()); row.put("balance", i.getBalance());
        row.put("note", i.getNote()); row.put("issuedAt", i.getIssuedAt()); row.put("dueAt", i.getDueAt()); row.put("paidAt", i.getPaidAt()); row.put("voidedAt", i.getVoidedAt());
        row.put("rateCardVersion", i.getRateCardVersion()); row.put("rateCardName", i.getRateCardName()); row.put("dateCreated", i.getDateCreated());
        return row;
    }

    @RequestMapping(value = "/account", method = RequestMethod.GET)
    public ResponseEntity<?> account(@RequestParam(required = false) Long tenantId) {
        Long scoped = this.scope(tenantId);
        if (scoped == null) return this.refused("No workspace to read.");
        return this.ok("Billing account.", this.billing.accountFor(scoped));
    }

    @RequestMapping(value = "/account", method = RequestMethod.POST)
    public ResponseEntity<?> saveAccount(@RequestParam(required = false) Long tenantId, @RequestBody BillingAccount changes) {
        Long scoped = this.scope(tenantId);
        if (scoped == null) return this.refused("No workspace to save for.");
        return this.ok("Billing account saved.", this.billing.saveAccount(scoped, changes));
    }

    @RequestMapping(value = "/invoices", method = RequestMethod.GET)
    public ResponseEntity<?> invoices(@RequestParam(required = false) Long tenantId) {
        Long scoped = this.scope(tenantId);
        if (scoped == null && !TenantContext.isPlatformAdmin()) return this.refused("No workspace to read.");
        Map<Long, String> tenantNames = this.billing.tenantNames();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice i : this.billing.invoicesFor(scoped)) rows.add(this.invoiceRow(i, tenantNames));
        return this.ok("Invoices.", rows);
    }

    @RequestMapping(value = "/invoice", method = RequestMethod.GET)
    public ResponseEntity<?> invoice(@RequestParam String number) {
        Optional<Invoice> found = this.billing.byNumber(number);
        if (!found.isPresent() || !this.mayTouch(found.get().getTenantId())) return this.refused("No such invoice.");
        Invoice i = found.get();
        Map<String, Object> out = this.invoiceRow(i, this.billing.tenantNames());
        out.put("lines", this.billing.linesOf(i.getInvoiceId()));
        List<Payment> paymentRows = this.billing.paymentsOf(i.getInvoiceId());
        Set<Long> ids = new HashSet<>();
        for (Payment p : paymentRows) { if (p.getSubmittedBy() != null) ids.add(p.getSubmittedBy()); if (p.getVerifiedBy() != null) ids.add(p.getVerifiedBy()); }
        List<BillingDocument> docs = this.billing.documentsOf(i.getInvoiceId());
        for (BillingDocument d : docs) if (d.getCreatedBy() != null) ids.add(d.getCreatedBy());
        if (i.getCreatedBy() != null) ids.add(i.getCreatedBy());
        Map<Long, String> people = this.billing.userNames(ids);
        List<Map<String, Object>> paymentsOut = new ArrayList<>();
        for (Payment p : paymentRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", p.getPaymentId()); row.put("amount", p.getAmount()); row.put("method", p.getMethod()); row.put("reference", p.getReference()); row.put("note", p.getNote());
            row.put("status", p.getStatus()); row.put("receiptNumber", p.getReceiptNumber()); row.put("submittedBy", people.get(p.getSubmittedBy())); row.put("verifiedBy", people.get(p.getVerifiedBy()));
            row.put("verifiedAt", p.getVerifiedAt()); row.put("receivedAt", p.getReceivedAt()); row.put("dateCreated", p.getDateCreated()); row.put("hasSlip", p.getSlipObjectKey() != null);
            paymentsOut.add(row);
        }
        out.put("payments", paymentsOut);
        List<Map<String, Object>> docsOut = new ArrayList<>();
        for (BillingDocument d : docs) docsOut.add(this.documentRow(d, people, null));
        out.put("documents", docsOut);
        out.put("account", this.billing.accountFor(i.getTenantId()));
        if (i.getReferencesInvoiceId() != null) out.put("referencesNumber", this.billing.find(i.getReferencesInvoiceId()).getNumber());
        out.put("createdByName", people.get(i.getCreatedBy()));
        return this.ok("Invoice.", out);
    }

    private Map<String, Object> documentRow(BillingDocument d, Map<Long, String> people, Map<Long, String> tenantNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", d.getBillingDocumentId()); row.put("kind", d.getKind()); row.put("number", d.getNumber()); row.put("fileName", d.getFileName());
        row.put("contentType", d.getContentType()); row.put("sizeBytes", d.getSizeBytes()); row.put("amount", d.getAmount()); row.put("issuedAt", d.getIssuedAt());
        row.put("invoiceId", d.getInvoiceId()); row.put("paymentId", d.getPaymentId()); row.put("tenantId", d.getTenantId());
        row.put("createdByName", people == null ? null : people.get(d.getCreatedBy()));
        if (tenantNames != null) row.put("tenantName", tenantNames.get(d.getTenantId()));
        if (d.getInvoiceId() != null) { try { row.put("invoiceNumber", this.billing.find(d.getInvoiceId()).getNumber()); } catch (RuntimeException ignored) { /* a document of a deleted invoice */ } }
        return row;
    }

    /** Builds (or rebuilds) the month's draft from the meter. Platform admin. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/invoice/draft", method = RequestMethod.POST)
    public ResponseEntity<?> draft(@RequestParam Long tenantId, @RequestParam String period) {
        try {
            Invoice i = this.billing.draft(tenantId, YearMonth.parse(period));
            return this.ok(String.format("Draft %s for %s: %s %s.", i.getNumber(), period, i.getCurrency(), i.getTotal()), this.invoiceRow(i, this.billing.tenantNames()));
        } catch (RuntimeException ex) { return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/invoice/line", method = RequestMethod.POST)
    public ResponseEntity<?> addLine(@RequestParam Long invoiceId, @RequestParam String description, @RequestParam BigDecimal quantity, @RequestParam BigDecimal unitPrice) {
        try { return this.ok("Line added.", this.billing.addManualLine(invoiceId, description, quantity, unitPrice)); }
        catch (RuntimeException ex) { return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/invoice/issue", method = RequestMethod.POST)
    public ResponseEntity<?> issue(@RequestParam Long invoiceId) {
        try { Invoice i = this.billing.issue(invoiceId); return this.ok(String.format("%s issued: %s %s, due %s.", i.getNumber(), i.getCurrency(), i.getTotal(), i.getDueAt()), this.invoiceRow(i, this.billing.tenantNames())); }
        catch (Exception ex) { this.logger.warn("issue failed: {}", ex.toString()); return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/invoice/void", method = RequestMethod.POST)
    public ResponseEntity<?> voidInvoice(@RequestParam Long invoiceId, @RequestParam String reason) {
        try { return this.ok("Invoice voided.", this.invoiceRow(this.billing.voidInvoice(invoiceId, reason), this.billing.tenantNames())); }
        catch (RuntimeException ex) { return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/invoice/creditNote", method = RequestMethod.POST)
    public ResponseEntity<?> creditNote(@RequestParam Long invoiceId, @RequestParam BigDecimal amount, @RequestParam String reason) {
        try { Invoice n = this.billing.creditNote(invoiceId, amount, reason); return this.ok(String.format("Credit note %s issued for %s.", n.getNumber(), amount), this.invoiceRow(n, this.billing.tenantNames())); }
        catch (Exception ex) { return this.refused(ex.getMessage()); }
    }

    /** A workspace says it paid: amount, method, reference and the slip. */
    @RequestMapping(value = "/payment/submit", method = RequestMethod.POST)
    public ResponseEntity<?> submitPayment(@RequestParam Long invoiceId, @RequestParam BigDecimal amount, @RequestParam(required = false) String method,
        @RequestParam(required = false) String reference, @RequestParam(required = false) String note, @RequestParam(value = "slip", required = false) MultipartFile slip) {
        try {
            Invoice i = this.billing.find(invoiceId);
            if (!this.mayTouch(i.getTenantId())) return this.refused("No such invoice.");
            Payment p = this.billing.submitPayment(invoiceId, amount, method, reference, note, slip);
            return this.ok(String.format("Payment of %s recorded; it counts once the platform verifies it.", p.getAmount()), p);
        } catch (Exception ex) { return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/payment/verify", method = RequestMethod.POST)
    public ResponseEntity<?> verifyPayment(@RequestParam Long paymentId, @RequestParam boolean accept, @RequestParam(required = false) String note) {
        try { Payment p = this.billing.verifyPayment(paymentId, accept, note); return this.ok(accept ? "Payment verified; receipt " + p.getReceiptNumber() + " issued." : "Payment rejected.", p); }
        catch (Exception ex) { return this.refused(ex.getMessage()); }
    }

    @RequestMapping(value = "/documents", method = RequestMethod.GET)
    public ResponseEntity<?> documents(@RequestParam(required = false) Long tenantId) {
        Long scoped = this.scope(tenantId);
        if (scoped == null && !TenantContext.isPlatformAdmin()) return this.refused("No workspace to read.");
        List<BillingDocument> docs = this.billing.documentsFor(scoped);
        Set<Long> ids = new HashSet<>();
        for (BillingDocument d : docs) if (d.getCreatedBy() != null) ids.add(d.getCreatedBy());
        Map<Long, String> people = this.billing.userNames(ids);
        Map<Long, String> tenantNames = this.billing.tenantNames();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillingDocument d : docs) rows.add(this.documentRow(d, people, tenantNames));
        return this.ok("Documents.", rows);
    }

    /** The bytes of one document, served here so a workspace never needs the platform bucket. */
    @RequestMapping(value = "/document", method = RequestMethod.GET)
    public ResponseEntity<?> document(@RequestParam Long documentId, @RequestParam(required = false, defaultValue = "inline") String disposition) {
        try {
            BillingDocument d = this.billing.document(documentId);
            if (!this.mayTouch(d.getTenantId())) return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "No such document."), HttpStatus.NOT_FOUND);
            ObjectContentDto content = this.billing.bytesOf(d);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, ("attachment".equals(disposition) ? "attachment" : "inline") + "; filename=\"" + d.getFileName() + "\"");
            return ResponseEntity.ok().headers(headers).contentType(MediaType.parseMediaType(d.getContentType() == null ? "application/octet-stream" : d.getContentType()))
                .contentLength(content.getSize()).body(new InputStreamResource(content.getContent()));
        } catch (RuntimeException ex) { return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.NOT_FOUND); }
    }

    @RequestMapping(value = "/statement", method = RequestMethod.POST)
    public ResponseEntity<?> statement(@RequestParam(required = false) Long tenantId, @RequestParam String from, @RequestParam String to) {
        Long scoped = this.scope(tenantId);
        if (scoped == null) return this.refused("No workspace to prepare a statement for.");
        try { BillingDocument d = this.billing.statement(scoped, LocalDate.parse(from), LocalDate.parse(to)); return this.ok("Statement " + d.getNumber() + " prepared.", this.documentRow(d, null, null)); }
        catch (Exception ex) { return this.refused(ex.getMessage()); }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/analytics", method = RequestMethod.GET)
    public ResponseEntity<?> analytics(@RequestParam String from, @RequestParam String to) {
        try {
            Map<String, Object> out = this.billing.analytics(LocalDate.parse(from), LocalDate.parse(to));
            // Data churn per workspace, from the meter, for the same range.
            if (this.meter.isConfigured()) {
                try { out.put("usageByTenant", this.meter.usage(null, LocalDate.parse(from), LocalDate.parse(to), "tenant").get("rows")); } catch (RuntimeException ex) { out.put("usageByTenant", null); }
            }
            return this.ok("Billing analytics.", out);
        } catch (RuntimeException ex) { return this.refused(ex.getMessage()); }
    }

    /** Close a month for every active workspace: a draft each, from the meter. Platform admin. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/closeMonth", method = RequestMethod.POST)
    public ResponseEntity<?> closeMonth(@RequestParam String period) {
        int drafted = 0;
        for (Map.Entry<Long, String> t : this.billing.tenantNames().entrySet()) {
            try { this.billing.draft(t.getKey(), YearMonth.parse(period)); drafted++; }
            catch (RuntimeException ex) { this.logger.warn("draft for tenant {} failed: {}", t.getKey(), ex.toString()); }
        }
        return this.ok(String.format("%d draft(s) for %s.", drafted, period), drafted);
    }

    /** The meter knows who by id; a person reading "who deleted what" wants the name. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withActorNames(Map<String, Object> answer) {
        Object rows = answer.get("rows");
        if (!(rows instanceof List)) {
            return answer;
        }
        Set<Long> ids = new HashSet<>();
        for (Object row : (List<Object>) rows) {
            Object id = ((Map<String, Object>) row).get("actor_user_id");
            if (id instanceof Number) ids.add(((Number) id).longValue());
        }
        Map<Long, String> resolved = ids.isEmpty() ? new HashMap<>() : this.names.namesFor(ids);
        for (Object row : (List<Object>) rows) {
            Map<String, Object> r = (Map<String, Object>) row;
            Object id = r.get("actor_user_id");
            r.put("actor_name", id instanceof Number ? resolved.get(((Number) id).longValue()) : null);
        }
        return answer;
    }

    /** Tonight's measurement, now: storage kept, seats and topics for every workspace. Platform admin. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/measure", method = RequestMethod.POST)
    public ResponseEntity<?> measure() {
        if (!this.meter.isConfigured()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "Metering is not configured on this console."), HttpStatus.OK);
        }
        int events = this.measurer.measure(LocalDate.now());
        this.meter.flush();
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, String.format("Measured: %d event(s) reported.", events), events), HttpStatus.OK);
    }

    private Long scope(Long tenantId) {
        if (TenantContext.isPlatformAdmin()) {
            return tenantId;
        }
        return TenantContext.getTenantId();
    }

    private ResponseEntity<?> answer(String what, JsonSupplier body) {
        if (!this.meter.isConfigured()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "Metering is not configured on this console."), HttpStatus.OK);
        }
        try {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, what, body.get()), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.warn("billing {} failed: {}", what, ex.toString());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "The metering service did not answer: " + ex.getMessage()), HttpStatus.OK);
        }
    }

    @RequestMapping(value = "/usage", method = RequestMethod.GET)
    public ResponseEntity<?> usage(@RequestParam(required = false) Long tenantId, @RequestParam String from, @RequestParam String to,
        @RequestParam(required = false, defaultValue = "meter") String groupBy) {
        Long scoped = this.scope(tenantId);
        if (scoped == null && !"tenant".equals(groupBy) && !TenantContext.isPlatformAdmin()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "No workspace to read."), HttpStatus.OK);
        }
        return this.answer("Usage read.", () -> this.meter.usage(scoped, LocalDate.parse(from), LocalDate.parse(to), groupBy));
    }

    @RequestMapping(value = "/subjects", method = RequestMethod.GET)
    public ResponseEntity<?> subjects(@RequestParam(required = false) Long tenantId, @RequestParam("meter") String meterName,
        @RequestParam String from, @RequestParam String to, @RequestParam(required = false, defaultValue = "50") int limit) {
        Long scoped = this.scope(tenantId);
        return this.answer("Subjects read.", () -> this.withActorNames(this.meter.subjects(scoped, meterName, LocalDate.parse(from), LocalDate.parse(to), limit)));
    }

    @RequestMapping(value = "/events", method = RequestMethod.GET)
    public ResponseEntity<?> events(@RequestParam(required = false) Long tenantId, @RequestParam(value = "meter", required = false) String meterName,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false, defaultValue = "1") int page, @RequestParam(required = false, defaultValue = "100") int limit) {
        Long scoped = this.scope(tenantId);
        return this.answer("Events read.", () -> this.withActorNames(this.meter.events(scoped, meterName,
            from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to), page, limit)));
    }

    // ---- rate cards: the calculation, versioned ---------------------------------------------

    /**
     * One version by number, or the card that prices a workspace on a day (today by default).
     * A workspace admin reads their own card only; a platform admin any.
     */
    @RequestMapping(value = "/rateCard", method = RequestMethod.GET)
    public ResponseEntity<?> rateCard(@RequestParam(required = false) Integer version, @RequestParam(required = false) Long tenantId,
        @RequestParam(required = false) String day) {
        if (version != null && !TenantContext.isPlatformAdmin()) return this.refused("Only the platform reads rate card versions.");
        Long scoped = this.scope(tenantId);
        LocalDate on = day == null ? LocalDate.now() : LocalDate.parse(day);
        return this.answer("Rate card read.", () -> version != null ? this.meter.rateCard(version) : this.meter.rateCardFor(scoped, on));
    }

    /** Every version, newest first: the default cards and the ones a workspace has of its own. */
    @RequestMapping(value = "/rateCards", method = RequestMethod.GET)
    public ResponseEntity<?> rateCards() {
        if (!TenantContext.isPlatformAdmin()) return this.refused("Only the platform reads the rate cards.");
        return this.answer("Rate cards read.", () -> {
            Map<String, Object> out = this.meter.rateCards();
            Map<Long, String> tenantNames = this.billing.tenantNames();
            if (out.get("cards") instanceof List) {
                for (Object o : (List<?>) out.get("cards")) {
                    if (!(o instanceof Map)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> card = (Map<String, Object>) o;
                    Object tenant = card.get("tenant_id");
                    card.put("tenantName", tenant instanceof Number ? tenantNames.get(((Number) tenant).longValue()) : null);
                }
            }
            return out;
        });
    }

    /**
     * A new version of the calculation. Nothing is edited in place: the version saved here prices
     * every bill drafted for a period from its effective date on, for the workspace it names or,
     * without one, for every workspace that has no card of its own. Bills already drafted keep
     * the version they were priced with.
     */
    @RequestMapping(value = "/rateCard", method = RequestMethod.PUT)
    public ResponseEntity<?> saveRateCard(@RequestBody Map<String, Object> card) {
        if (!TenantContext.isPlatformAdmin()) return this.refused("Only the platform changes the calculation.");
        Object name = card.get("name");
        if (name == null || String.valueOf(name).trim().isEmpty()) return this.refused("Give the version a name -- what changed, or who it is for.");
        if (card.get("effective_from") == null) return this.refused("Say when the version takes effect.");
        return this.answer("Rate card version saved.", () -> this.meter.saveRateCard(card));
    }

    /** Rolls the last two days again, for a Refresh that wants the latest events priced now. */
    @RequestMapping(value = "/refresh", method = RequestMethod.POST)
    public ResponseEntity<?> refresh() {
        return this.answer("Rolled up.", () -> this.meter.rollup(48));
    }

    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public ResponseEntity<?> health() {
        return this.answer("Meter health.", this.meter::health);
    }

    @FunctionalInterface
    private interface JsonSupplier { Map<String, Object> get() throws Exception; }
}
