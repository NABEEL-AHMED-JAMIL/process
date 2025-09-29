package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum APPLICATION_STATUS {

    INACTIVE("INACTIVE", 0l, "Inactive"),
    ACTIVE("ACTIVE", 1l, "Active"),
    DELETE("DELETE", 2l, "Delete");

    private String lookupType;
    private Long lookupValue;
    private String description;

    APPLICATION_STATUS(String lookupType, Long lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public static GLookup getStatusByValue(Long lookupValue) {
        APPLICATION_STATUS applicationStatus = null;
        if (lookupValue.equals(INACTIVE.lookupValue)) {
            applicationStatus = INACTIVE;
        } else if (lookupValue.equals(ACTIVE.lookupValue)) {
            applicationStatus = ACTIVE;
        } else if (lookupValue.equals(DELETE.lookupValue)) {
            applicationStatus = DELETE;
        }
        return new GLookup(applicationStatus.lookupType, applicationStatus.lookupValue, applicationStatus.description);
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
