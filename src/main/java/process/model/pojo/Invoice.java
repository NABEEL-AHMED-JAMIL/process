package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;


/** A month closed into a bill: draft until issued, frozen afterwards; a credit note is one with kind credit_note and a negative total. */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "invoice")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Invoice {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "invoice_id") private Long invoiceId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "number") private String number;
    @Column(name = "kind") private String kind;
    @Column(name = "references_invoice_id") private Long referencesInvoiceId;
    @Column(name = "period_start") private LocalDate periodStart;
    @Column(name = "period_end") private LocalDate periodEnd;
    @Column(name = "status") private String status;
    @Column(name = "currency") private String currency;
    @Column(name = "subtotal") private BigDecimal subtotal;
    @Column(name = "tax_rate_percent") private BigDecimal taxRatePercent;
    @Column(name = "tax") private BigDecimal tax;
    @Column(name = "total") private BigDecimal total;
    @Column(name = "balance") private BigDecimal balance;
    @Column(name = "note") private String note;
    @Column(name = "issued_at") private Timestamp issuedAt;
    @Column(name = "due_at") private Timestamp dueAt;
    @Column(name = "paid_at") private Timestamp paidAt;
    @Column(name = "voided_at") private Timestamp voidedAt;
    @Column(name = "pdf_object_key") private String pdfObjectKey;
    @Column(name = "rate_card_version") private Integer rateCardVersion;
    @Column(name = "rate_card_name") private String rateCardName;
    @Column(name = "date_created") private Timestamp dateCreated;
    @Column(name = "date_updated") private Timestamp dateUpdated;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "updated_by") private Long updatedBy;

    public Long getInvoiceId() { return invoiceId; } public void setInvoiceId(Long v) { invoiceId = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public String getNumber() { return number; } public void setNumber(String v) { number = v; }
    public String getKind() { return kind; } public void setKind(String v) { kind = v; }
    public Long getReferencesInvoiceId() { return referencesInvoiceId; } public void setReferencesInvoiceId(Long v) { referencesInvoiceId = v; }
    public LocalDate getPeriodStart() { return periodStart; } public void setPeriodStart(LocalDate v) { periodStart = v; }
    public LocalDate getPeriodEnd() { return periodEnd; } public void setPeriodEnd(LocalDate v) { periodEnd = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getCurrency() { return currency; } public void setCurrency(String v) { currency = v; }
    public BigDecimal getSubtotal() { return subtotal; } public void setSubtotal(BigDecimal v) { subtotal = v; }
    public BigDecimal getTaxRatePercent() { return taxRatePercent; } public void setTaxRatePercent(BigDecimal v) { taxRatePercent = v; }
    public BigDecimal getTax() { return tax; } public void setTax(BigDecimal v) { tax = v; }
    public BigDecimal getTotal() { return total; } public void setTotal(BigDecimal v) { total = v; }
    public BigDecimal getBalance() { return balance; } public void setBalance(BigDecimal v) { balance = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public Timestamp getIssuedAt() { return issuedAt; } public void setIssuedAt(Timestamp v) { issuedAt = v; }
    public Timestamp getDueAt() { return dueAt; } public void setDueAt(Timestamp v) { dueAt = v; }
    public Timestamp getPaidAt() { return paidAt; } public void setPaidAt(Timestamp v) { paidAt = v; }
    public Timestamp getVoidedAt() { return voidedAt; } public void setVoidedAt(Timestamp v) { voidedAt = v; }
    public String getPdfObjectKey() { return pdfObjectKey; } public void setPdfObjectKey(String v) { pdfObjectKey = v; }
    public Integer getRateCardVersion() { return rateCardVersion; } public void setRateCardVersion(Integer v) { rateCardVersion = v; }
    public String getRateCardName() { return rateCardName; } public void setRateCardName(String v) { rateCardName = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
    public Timestamp getDateUpdated() { return dateUpdated; } public void setDateUpdated(Timestamp v) { dateUpdated = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Long getUpdatedBy() { return updatedBy; } public void setUpdatedBy(Long v) { updatedBy = v; }
}
