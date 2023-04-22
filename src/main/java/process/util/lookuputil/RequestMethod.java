package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum RequestMethod {

    GET("GET", 0l, "GET"),
    HEAD("HEAD", 1l, "HEAD"),
    POST("POST", 2l, "POST"),
    PUT("PUT", 3l, "PUT"),
    PATCH("PATCH", 4l, "PATCH");

    private String lookupType;
    private Long lookupValue;
    private String description;

    RequestMethod(String lookupType, Long lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public static GLookup getRequestMethodByValue(Integer lookupValue) {
        RequestMethod requestMethod = null;
        if (lookupValue == 0) {
            requestMethod = GET;
        } else if (lookupValue == 1) {
            requestMethod = HEAD;
        } else if (lookupValue == 2) {
            requestMethod = POST;
        } else if (lookupValue == 3) {
            requestMethod = PUT;
        } else if (lookupValue == 4) {
            requestMethod = PATCH;
        }
        return new GLookup(requestMethod.lookupType,
            requestMethod.lookupValue, requestMethod.description);
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
