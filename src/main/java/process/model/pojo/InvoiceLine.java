package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;


/** One line of an invoice: a meter's month priced, a late line from an earlier period, or a manual line an admin added. */
@Entity
@Table(name = "invoice_line")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoiceLine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "invoice_line_id") private Long invoiceLineId;
    @Column(name = "invoice_id") private Long invoiceId;
    @Column(name = "sort") private Integer sort;
    @Column(name = "meter") private String meter;
    @Column(name = "description") private String description;
    @Column(name = "quantity") private BigDecimal quantity;
    @Column(name = "unit") private String unit;
    @Column(name = "per") private Integer per;
    @Column(name = "unit_price") private BigDecimal unitPrice;
    @Column(name = "amount") private BigDecimal amount;
    @Column(name = "period_label") private String periodLabel;
    @Column(name = "manual") private Boolean manual;
    @Column(name = "included_quantity") private BigDecimal includedQuantity;
    @Column(name = "billable_quantity") private BigDecimal billableQuantity;
    /** What the calculation applied, as the meter told it: the tier bands, in JSON. */
    @Column(name = "pricing_detail") private String pricingDetail;

    public Long getInvoiceLineId() { return invoiceLineId; } public void setInvoiceLineId(Long v) { invoiceLineId = v; }
    public Long getInvoiceId() { return invoiceId; } public void setInvoiceId(Long v) { invoiceId = v; }
    public Integer getSort() { return sort; } public void setSort(Integer v) { sort = v; }
    public String getMeter() { return meter; } public void setMeter(String v) { meter = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public BigDecimal getQuantity() { return quantity; } public void setQuantity(BigDecimal v) { quantity = v; }
    public String getUnit() { return unit; } public void setUnit(String v) { unit = v; }
    public Integer getPer() { return per; } public void setPer(Integer v) { per = v; }
    public BigDecimal getUnitPrice() { return unitPrice; } public void setUnitPrice(BigDecimal v) { unitPrice = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getPeriodLabel() { return periodLabel; } public void setPeriodLabel(String v) { periodLabel = v; }
    public Boolean getManual() { return manual; } public void setManual(Boolean v) { manual = v; }
    public BigDecimal getIncludedQuantity() { return includedQuantity; } public void setIncludedQuantity(BigDecimal v) { includedQuantity = v; }
    public BigDecimal getBillableQuantity() { return billableQuantity; } public void setBillableQuantity(BigDecimal v) { billableQuantity = v; }
    public String getPricingDetail() { return pricingDetail; } public void setPricingDetail(String v) { pricingDetail = v; }
}
