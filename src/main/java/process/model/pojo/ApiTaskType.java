package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.springframework.http.HttpMethod;
import javax.persistence.*;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "api_task_type")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiTaskType {

    @GenericGenerator(
        name = "apiTaskTypeSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "api_task_type_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="api_task_type_id", unique=true, nullable=false)
    @GeneratedValue(generator = "apiTaskTypeSequenceGenerator")
    private Long apiTaskTypeId;

    @Column(name = "api_url", nullable = false)
    private String apiUrl;

    @Column(name = "http_method", nullable = false)
    private HttpMethod httpMethod;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="stt_id")
    private STT stt;

    @Column(name = "status", nullable = false)
    private Long status;

    public ApiTaskType() {
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

    public STT getStt() {
        return stt;
    }

    public void setStt(STT stt) {
        this.stt = stt;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
