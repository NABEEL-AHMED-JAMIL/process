package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WeeklyJobStatisticsDto {

    private String dayCode;
    private Long hr;
    private String date;
    private Long count;
    private Long tenantId;
    private Boolean allWorkspaces;

    public WeeklyJobStatisticsDto() {}

    public WeeklyJobStatisticsDto(String dayCode, Long hr, Long count) {
        this.dayCode = dayCode;
        this.hr = hr;
        this.count = count;
    }

    public WeeklyJobStatisticsDto(String dayCode, Long hr, String date, Long count) {
        this.dayCode = dayCode;
        this.hr = hr;
        this.date = date;
        this.count = count;
    }

    public String getDayCode() {
        return dayCode;
    }

    public void setDayCode(String dayCode) {
        this.dayCode = dayCode;
    }

    public Long getHr() {
        return hr;
    }

    public void setHr(Long hr) {
        this.hr = hr;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
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
