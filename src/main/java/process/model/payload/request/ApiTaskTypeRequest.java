package process.model.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.springframework.http.HttpMethod;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiTaskTypeRequest {

    private Long apiTaskTypeId;
    private String apiUrl; // yes
    private HttpMethod httpMethod; // yes

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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
