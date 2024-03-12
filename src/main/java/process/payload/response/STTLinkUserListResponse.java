package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTLinkUserListResponse {

    private Long sttLinkUserId;
    private STTResponse stt;
    private Long appUserid;
    private String username;
    private String email;
    private GLookup status;
    private Timestamp dateCreated;

    public STTLinkUserListResponse() {
    }

    public Long getSttLinkUserId() {
        return sttLinkUserId;
    }

    public void setSttLinkUserId(Long sttLinkUserId) {
        this.sttLinkUserId = sttLinkUserId;
    }

    public STTResponse getStt() {
        return stt;
    }

    public void setStt(STTResponse stt) {
        this.stt = stt;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Long getAppUserid() {
        return appUserid;
    }

    public void setAppUserid(Long appUserid) {
        this.appUserid = appUserid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
