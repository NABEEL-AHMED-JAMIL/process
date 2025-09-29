package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CredentialRequest {

    private Long credentialId;
    private String credentialName; // yes
    private Long credentialType; // yes
    private Long status;
    private Object credentialContent; // yes
    private ParseRequest accessUserDetail; // yes

    public CredentialRequest() {
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

    public String getCredentialName() {
        return credentialName;
    }

    public void setCredentialName(String credentialName) {
        this.credentialName = credentialName;
    }

    public Long getCredentialType() {
        return credentialType;
    }

    public void setCredentialType(Long credentialType) {
        this.credentialType = credentialType;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Object getCredentialContent() {
        return credentialContent;
    }

    public void setCredentialContent(Object credentialContent) {
        this.credentialContent = credentialContent;
    }

    public ParseRequest getAccessUserDetail() {
        return accessUserDetail;
    }

    public void setAccessUserDetail(ParseRequest accessUserDetail) {
        this.accessUserDetail = accessUserDetail;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
