package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A tenant's override of which Kafka cluster to publish a given (usually shared/global)
 * SourceTaskType's messages to -- lets two tenants both use the same catalog task type (e.g.
 * "ETL Scrapping Pipeline") while publishing to two entirely different clusters, without
 * duplicating the SourceTaskType row itself. See KafkaConnectionResolver for exactly where this
 * fits in the overall resolution order (checked first, before the type's own default profile).
 * At most one route per (tenantId, sourceTaskTypeId) -- enforced by a unique constraint.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "tenant_task_type_kafka_route",
    uniqueConstraints = @UniqueConstraint(name = "uq_tenant_task_type",
        columnNames = {"tenant_id", "source_task_type_id"}),
    indexes = {
        @Index(name = "idx_ttkr_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_ttkr_task_type_id", columnList = "source_task_type_id"),
        @Index(name = "idx_ttkr_profile_id", columnList = "kafka_connection_profile_id")
    })
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

    @Column(name = "source_task_type_id", nullable = false)
    private Long sourceTaskTypeId;

    @Column(name = "kafka_connection_profile_id", nullable = false)
    private Long kafkaConnectionProfileId;

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

    public Long getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(Long sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public Long getKafkaConnectionProfileId() {
        return kafkaConnectionProfileId;
    }

    public void setKafkaConnectionProfileId(Long kafkaConnectionProfileId) {
        this.kafkaConnectionProfileId = kafkaConnectionProfileId;
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
