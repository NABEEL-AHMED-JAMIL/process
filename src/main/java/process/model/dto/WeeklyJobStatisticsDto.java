package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WeeklyJobStatisticsDto {

    private Long dayCode;
    private Long hr;
    private String date;
    private Long count;

    public WeeklyJobStatisticsDto() {}

    public WeeklyJobStatisticsDto(Long dayCode, Long hr, Long count) {
        this.dayCode = dayCode;
        this.hr = hr;
        this.count = count;
    }

    public WeeklyJobStatisticsDto(Long dayCode, Long hr, String date, Long count) {
        this.dayCode = dayCode;
        this.hr = hr;
        this.date = date;
        this.count = count;
    }

    public Long getDayCode() {
        return dayCode;
    }

    public void setDayCode(Long dayCode) {
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
