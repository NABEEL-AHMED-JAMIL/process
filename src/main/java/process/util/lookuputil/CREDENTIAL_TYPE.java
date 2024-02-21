package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum CREDENTIAL_TYPE {

    BASIC_AUTH("BASIC_AUTH", 0l, "Basic Auth"),
    CERTIFICATE("CERTIFICATE", 1l, "Certificate"),
    AUTHORIZATION_CODE("AUTHORIZATION_CODE", 2l, "Authorization Code"),
    AWS_AUTH("AWS_AUTH", 3l, "Aws Auth"),
    FIREBASE("FIREBASE", 4l, "Firebase"),
    FTP("FTP", 5l, "FTP");

    private String lookupType;
    private Long lookupValue;
    private String description;

    CREDENTIAL_TYPE(String lookupType, Long lookupValue,
                    String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public Long getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(Long lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public static GLookup getFormControlTypeByValue(Long lookupValue) {
        CREDENTIAL_TYPE credentialType = null;
        if (lookupValue.equals(BASIC_AUTH.lookupValue)) {
            credentialType = BASIC_AUTH;
        } else if (lookupValue.equals(CERTIFICATE.lookupValue)) {
            credentialType = CERTIFICATE;
        } else if (lookupValue.equals(AUTHORIZATION_CODE.lookupValue)) {
            credentialType = AUTHORIZATION_CODE;
        } else if (lookupValue.equals(AWS_AUTH.lookupValue)) {
            credentialType = AWS_AUTH;
        } else if (lookupValue.equals(FIREBASE.lookupValue)) {
            credentialType = FIREBASE;
        } else if (lookupValue.equals(FTP.lookupValue)) {
            credentialType = FTP;
        }
        return new GLookup(credentialType.lookupType, credentialType.lookupValue, credentialType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
