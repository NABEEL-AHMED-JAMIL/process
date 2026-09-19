package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;


/** Who is billed and how: the workspace's legal name, address, tax number and rate, currency, terms. */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "billing_account")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "billing_account_id") private Long billingAccountId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "legal_name") private String legalName;
    @Column(name = "address") private String address;
    @Column(name = "billing_email") private String billingEmail;
    @Column(name = "tax_id") private String taxId;
    @Column(name = "tax_rate_percent") private BigDecimal taxRatePercent;
    @Column(name = "tax_label") private String taxLabel;
    @Column(name = "currency") private String currency;
    @Column(name = "payment_terms_days") private Integer paymentTermsDays;
    @Column(name = "status") private String status;
    @Column(name = "date_created") private Timestamp dateCreated;
    @Column(name = "date_updated") private Timestamp dateUpdated;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "updated_by") private Long updatedBy;

    public Long getBillingAccountId() { return billingAccountId; } public void setBillingAccountId(Long v) { billingAccountId = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public String getLegalName() { return legalName; } public void setLegalName(String v) { legalName = v; }
    public String getAddress() { return address; } public void setAddress(String v) { address = v; }
    public String getBillingEmail() { return billingEmail; } public void setBillingEmail(String v) { billingEmail = v; }
    public String getTaxId() { return taxId; } public void setTaxId(String v) { taxId = v; }
    public BigDecimal getTaxRatePercent() { return taxRatePercent; } public void setTaxRatePercent(BigDecimal v) { taxRatePercent = v; }
    public String getTaxLabel() { return taxLabel; } public void setTaxLabel(String v) { taxLabel = v; }
    public String getCurrency() { return currency; } public void setCurrency(String v) { currency = v; }
    public Integer getPaymentTermsDays() { return paymentTermsDays; } public void setPaymentTermsDays(Integer v) { paymentTermsDays = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
    public Timestamp getDateUpdated() { return dateUpdated; } public void setDateUpdated(Timestamp v) { dateUpdated = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Long getUpdatedBy() { return updatedBy; } public void setUpdatedBy(Long v) { updatedBy = v; }
}
