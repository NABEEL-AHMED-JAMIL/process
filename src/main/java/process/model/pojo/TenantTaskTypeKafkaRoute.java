package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "tenant_task_type_kafka_route",
    uniqueConstraints = @UniqueConstraint(name = "uq_tenant_task_type",
        columnNames = {"tenant_id", "source_task_type_id"}),
    indexes = {
        @Index(name = "idx_ttkr_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_ttkr_task_type_id", columnList = "source_task_type_id"),
        @Index(name = "idx_ttkr_profile_id", columnList = "kafka_connection_profile_id")
    })
/**
 * @author Nabeel Ahmed
 * */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// A route always belongs to one tenant -- the column is not nullable and there is no shared
// row to admit -- so the plain condition is the whole rule here.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantTaskTypeKafkaRoute {

    @GenericGenerator(
        name = "tenantTaskTypeKafkaRouteSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "tenant_task_type_kafka_route_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "tenant_task_type_kafka_route_id")
    @GeneratedValue(generator = "tenantTaskTypeKafkaRouteSequenceGenerator")
    private Long tenantTaskTypeKafkaRouteId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "source_task_type_id", nullable = false)
    private Long sourceTaskTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_task_type_id", insertable = false, updatable = false)
    private SourceTaskType sourceTaskType;

    @Column(name = "kafka_connection_profile_id", nullable = false)
    private Long kafkaConnectionProfileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kafka_connection_profile_id", insertable = false, updatable = false)
    private KafkaConnectionProfile kafkaConnectionProfile;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public TenantTaskTypeKafkaRoute() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getTenantTaskTypeKafkaRouteId() {
        return tenantTaskTypeKafkaRouteId;
    }

    public void setTenantTaskTypeKafkaRouteId(Long tenantTaskTypeKafkaRouteId) {
        this.tenantTaskTypeKafkaRouteId = tenantTaskTypeKafkaRouteId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Long getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(Long sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public SourceTaskType getSourceTaskType() {
        return sourceTaskType;
    }

    public Long getKafkaConnectionProfileId() {
        return kafkaConnectionProfileId;
    }

    public void setKafkaConnectionProfileId(Long kafkaConnectionProfileId) {
        this.kafkaConnectionProfileId = kafkaConnectionProfileId;
    }

    public KafkaConnectionProfile getKafkaConnectionProfile() {
        return kafkaConnectionProfile;
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
