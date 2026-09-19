package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;


/** A payment against an invoice: submitted with a slip by the workspace, verified or rejected by the platform; a verified one carries its receipt. */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "payment")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "payment_id") private Long paymentId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "invoice_id") private Long invoiceId;
    @Column(name = "amount") private BigDecimal amount;
    @Column(name = "method") private String method;
    @Column(name = "reference") private String reference;
    @Column(name = "note") private String note;
    @Column(name = "status") private String status;
    @Column(name = "slip_object_key") private String slipObjectKey;
    @Column(name = "receipt_number") private String receiptNumber;
    @Column(name = "submitted_by") private Long submittedBy;
    @Column(name = "verified_by") private Long verifiedBy;
    @Column(name = "verified_at") private Timestamp verifiedAt;
    @Column(name = "received_at") private Timestamp receivedAt;
    @Column(name = "date_created") private Timestamp dateCreated;

    public Long getPaymentId() { return paymentId; } public void setPaymentId(Long v) { paymentId = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public Long getInvoiceId() { return invoiceId; } public void setInvoiceId(Long v) { invoiceId = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getMethod() { return method; } public void setMethod(String v) { method = v; }
    public String getReference() { return reference; } public void setReference(String v) { reference = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getSlipObjectKey() { return slipObjectKey; } public void setSlipObjectKey(String v) { slipObjectKey = v; }
    public String getReceiptNumber() { return receiptNumber; } public void setReceiptNumber(String v) { receiptNumber = v; }
    public Long getSubmittedBy() { return submittedBy; } public void setSubmittedBy(Long v) { submittedBy = v; }
    public Long getVerifiedBy() { return verifiedBy; } public void setVerifiedBy(Long v) { verifiedBy = v; }
    public Timestamp getVerifiedAt() { return verifiedAt; } public void setVerifiedAt(Timestamp v) { verifiedAt = v; }
    public Timestamp getReceivedAt() { return receivedAt; } public void setReceivedAt(Timestamp v) { receivedAt = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
}
