package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.springframework.http.HttpMethod;
import process.model.enums.Status;

import javax.persistence.Column;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiTaskTypeRequest {

    private Long apiTaskTypeId;
    private String apiUrl; // yes
    private HttpMethod httpMethod; // yes
    private Status status;
    // ref to lookup id
    private String apiSecurityIdMlu;
    private String apiSecurityIdSlu;


    public ApiTaskTypeRequest() {
    }

    public Long getApiTaskTypeId() {
        return apiTaskTypeId;
    }

    public void setApiTaskTypeId(Long apiTaskTypeId) {
        this.apiTaskTypeId = apiTaskTypeId;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getApiSecurityIdMlu() {
        return apiSecurityIdMlu;
    }

    public void setApiSecurityIdMlu(String apiSecurityIdMlu) {
        this.apiSecurityIdMlu = apiSecurityIdMlu;
    }

    public String getApiSecurityIdSlu() {
        return apiSecurityIdSlu;
    }

    public void setApiSecurityIdSlu(String apiSecurityIdSlu) {
        this.apiSecurityIdSlu = apiSecurityIdSlu;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
