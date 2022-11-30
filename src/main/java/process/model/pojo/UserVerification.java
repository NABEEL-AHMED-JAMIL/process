package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;


@Entity
@Table(name = "user_verification")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserVerification {

    @GenericGenerator(
        name = "userVerificationGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator", parameters = {
        @Parameter(name = "sequence_name", value = "user_verification_seq"),
        @Parameter(name = "initial_value", value = "1000"),
        @Parameter(name = "increment_size", value = "1")
    })
    @Id
    @GeneratedValue(generator = "userVerificationGenerator")
    private Long id;
    @Column(nullable = false)
    private String token;
    private Timestamp expiryDate;
    private Boolean passwordAdded;
    private Boolean isConsumed;
    private Timestamp activatedAt;

    public UserVerification() {}

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }
    public void setToken(String token) {
        this.token = token;
    }

    public Timestamp getExpiryDate() {
        return expiryDate;
    }
    public void setExpiryDate(Timestamp expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Boolean getPasswordAdded() {
        return passwordAdded;
    }
    public void setPasswordAdded(Boolean passwordAdded) {
        this.passwordAdded = passwordAdded;
    }

    public Boolean getConsumed() {
        return isConsumed;
    }
    public void setConsumed(Boolean consumed) {
        isConsumed = consumed;
    }

    public Timestamp getActivatedAt() {
        return activatedAt;
    }
    public void setActivatedAt(Timestamp activatedAt) {
        this.activatedAt = activatedAt;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
