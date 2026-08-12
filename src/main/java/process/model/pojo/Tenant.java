package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.TenantStatus;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A tenant (organization) -- every tenant-owned row across the app (SourceJob, SourceTask,
 * DynamicForm, AiAgent, PdfHighlighterTask, and their AppUsers) carries this tenant's id.
 * Provisioned by a Platform Admin only (see TenantRestApi) -- no public signup.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "tenant")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tenant {

    @GenericGenerator(
        name = "tenantSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "tenant_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "tenant_id")
    @GeneratedValue(generator = "tenantSequenceGenerator")
    private Long tenantId;

    @Column(name = "uuid", unique = true, length = 36)
    private String uuid;

    @Column(name = "tenant_name", nullable = false)
    private String tenantName;

    /** URL-safe unique short code, e.g. "acme-corp" -- not currently used in any route (no
     * per-tenant subdomain/path routing yet), reserved for that later without a migration. */
    @Column(name = "tenant_code", nullable = false, unique = true)
    private String tenantCode;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TenantStatus status;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public Tenant() {}

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
