package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.TenantStatus;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantDto implements AuditNamed {
    private String createdByName;
    private String updatedByName;
    private Long createdBy;


    private Long tenantId;
    private String uuid;
    private String tenantName;
    private String tenantCode;
    private TenantStatus status;
    private Timestamp dateCreated;

    private Long userCount;
    private Long kafkaProfileCount;
    private Long bucketCount;
    private Long sourceTaskTypeCount;
    private Long sourceTaskCount;
    /** Distinct pipelines across this tenant's tasks -- breadth, where sourceTaskCount is volume. */
    private Long pipelineCount;
    private Long sourceJobCount;

    public TenantDto() {}

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

    public Long getUserCount() {
        return userCount;
    }

    public void setUserCount(Long userCount) {
        this.userCount = userCount;
    }

    public Long getKafkaProfileCount() {
        return kafkaProfileCount;
    }

    public void setKafkaProfileCount(Long kafkaProfileCount) {
        this.kafkaProfileCount = kafkaProfileCount;
    }

    public Long getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(Long bucketCount) {
        this.bucketCount = bucketCount;
    }

    public Long getSourceTaskTypeCount() {
        return sourceTaskTypeCount;
    }

    public void setSourceTaskTypeCount(Long sourceTaskTypeCount) {
        this.sourceTaskTypeCount = sourceTaskTypeCount;
    }

    public Long getSourceTaskCount() {
        return sourceTaskCount;
    }

    public void setSourceTaskCount(Long sourceTaskCount) {
        this.sourceTaskCount = sourceTaskCount;
    }

    public Long getPipelineCount() {
        return pipelineCount;
    }

    public void setPipelineCount(Long pipelineCount) {
        this.pipelineCount = pipelineCount;
    }

    public Long getSourceJobCount() {
        return sourceJobCount;
    }

    public void setSourceJobCount(Long sourceJobCount) {
        this.sourceJobCount = sourceJobCount;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }


    @Override
    public Long auditKey() {
        return tenantId;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
