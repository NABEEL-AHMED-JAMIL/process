package process.model.pojo;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import java.sql.Timestamp;

/**
 * A request from outside the platform for a workspace of its own.
 *
 * It is stored rather than acted on because it arrives from an unauthenticated form: until a
 * platform administrator agrees, it is a claim about who someone is and what they want. The
 * tenant and its first administrator are created on approval, and this keeps a pointer to both
 * so a decision can be traced back afterwards.
 */
@Entity
@Table(name = "tenant_request")
public class TenantRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tenant_request_seq")
    @SequenceGenerator(name = "tenant_request_seq", sequenceName = "tenant_request_seq", allocationSize = 1)
    @Column(name = "tenant_request_id")
    private Long tenantRequestId;

    @Column(name = "organisation_name", nullable = false)
    private String organisationName;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "purpose")
    private String purpose;

    /** Pending, Approved or Rejected. */
    @Column(name = "status", nullable = false)
    private String status = "Pending";

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    @Column(name = "decided_at")
    private Timestamp decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_tenant_id")
    private Long createdTenantId;

    @Column(name = "created_user_id")
    private Long createdUserId;

    public Long getTenantRequestId() { return tenantRequestId; }
    public void setTenantRequestId(Long tenantRequestId) { this.tenantRequestId = tenantRequestId; }

    public String getOrganisationName() { return organisationName; }
    public void setOrganisationName(String organisationName) { this.organisationName = organisationName; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    public Timestamp getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Timestamp decidedAt) { this.decidedAt = decidedAt; }

    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public Long getCreatedTenantId() { return createdTenantId; }
    public void setCreatedTenantId(Long createdTenantId) { this.createdTenantId = createdTenantId; }

    public Long getCreatedUserId() { return createdUserId; }
    public void setCreatedUserId(Long createdUserId) { this.createdUserId = createdUserId; }
}
