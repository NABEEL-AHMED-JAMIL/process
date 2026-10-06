package process.customer;

/** An event route as the console saves it (MIG-332): a new one has no routeId. */
public class EventRouteRequest {

    private Long routeId;
    private String eventType;
    /** PIPELINE or WORKFLOW. */
    private String targetKind;
    private Long jobId;
    private String workflowKey;
    private Long contractId;
    private String contractName;
    private Integer contractVersion;
    private Boolean active;

    public Long getRouteId() {
        return this.routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public String getEventType() {
        return this.eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTargetKind() {
        return this.targetKind;
    }

    public void setTargetKind(String targetKind) {
        this.targetKind = targetKind;
    }

    public Long getJobId() {
        return this.jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getWorkflowKey() {
        return this.workflowKey;
    }

    public void setWorkflowKey(String workflowKey) {
        this.workflowKey = workflowKey;
    }

    public Long getContractId() {
        return this.contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public String getContractName() {
        return this.contractName;
    }

    public void setContractName(String contractName) {
        this.contractName = contractName;
    }

    public Integer getContractVersion() {
        return this.contractVersion;
    }

    public void setContractVersion(Integer contractVersion) {
        this.contractVersion = contractVersion;
    }

    public Boolean getActive() {
        return this.active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
