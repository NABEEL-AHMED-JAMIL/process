package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;


/** A billing document -- invoice PDF, receipt, credit note, statement, payment slip -- and where its bytes live in the platform's config bucket. */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "billing_document")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "billing_document_id") private Long billingDocumentId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "invoice_id") private Long invoiceId;
    @Column(name = "payment_id") private Long paymentId;
    @Column(name = "kind") private String kind;
    @Column(name = "number") private String number;
    @Column(name = "file_name") private String fileName;
    @Column(name = "content_type") private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(name = "object_key") private String objectKey;
    @Column(name = "amount") private BigDecimal amount;
    @Column(name = "issued_at") private Timestamp issuedAt;
    @Column(name = "created_by") private Long createdBy;

    public Long getBillingDocumentId() { return billingDocumentId; } public void setBillingDocumentId(Long v) { billingDocumentId = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public Long getInvoiceId() { return invoiceId; } public void setInvoiceId(Long v) { invoiceId = v; }
    public Long getPaymentId() { return paymentId; } public void setPaymentId(Long v) { paymentId = v; }
    public String getKind() { return kind; } public void setKind(String v) { kind = v; }
    public String getNumber() { return number; } public void setNumber(String v) { number = v; }
    public String getFileName() { return fileName; } public void setFileName(String v) { fileName = v; }
    public String getContentType() { return contentType; } public void setContentType(String v) { contentType = v; }
    public Long getSizeBytes() { return sizeBytes; } public void setSizeBytes(Long v) { sizeBytes = v; }
    public String getObjectKey() { return objectKey; } public void setObjectKey(String v) { objectKey = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public Timestamp getIssuedAt() { return issuedAt; } public void setIssuedAt(Timestamp v) { issuedAt = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
}
