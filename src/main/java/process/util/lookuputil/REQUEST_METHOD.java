package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum REQUEST_METHOD {

    GET("GET", 0l, "GET"),
    HEAD("HEAD", 1l, "HEAD"),
    POST("POST", 2l, "POST"),
    PUT("PUT", 3l, "PUT"),
    PATCH("PATCH", 4l, "PATCH");

    private String lookupType;
    private Long lookupValue;
    private String description;

    REQUEST_METHOD(String lookupType, Long lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public static GLookup getRequestMethodByValue(Long lookupValue) {
        REQUEST_METHOD requestMethod = null;
        if (lookupValue.equals(GET.getLookupValue())) {
            requestMethod = GET;
        } else if (lookupValue.equals(HEAD.getLookupValue())) {
            requestMethod = HEAD;
        } else if (lookupValue.equals(POST.getLookupValue())) {
            requestMethod = POST;
        } else if (lookupValue.equals(PUT.getLookupValue())) {
            requestMethod = PUT;
        } else if (lookupValue.equals(PATCH.getLookupValue())) {
            requestMethod = PATCH;
        }
        return new GLookup(requestMethod.lookupType, requestMethod.lookupValue, requestMethod.description);
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
