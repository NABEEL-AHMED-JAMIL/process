package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatusStatisticDto {

    private String name;
    private Integer value;
    private Long tenantId;
    private Boolean allWorkspaces;

    public JobStatusStatisticDto() {}

    public JobStatusStatisticDto(String name, Integer value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }

    /** The workspace these numbers are for; absent when they span every workspace (MIG-46). */
    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    /** True when a platform administrator's numbers add up every workspace (MIG-46). */
    public Boolean getAllWorkspaces() {
        return allWorkspaces;
    }

    public void setAllWorkspaces(Boolean allWorkspaces) {
        this.allWorkspaces = allWorkspaces;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
