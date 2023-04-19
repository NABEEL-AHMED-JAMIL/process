package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum Status {

    INACTIVE("INACTIVE", 0l, "Inactive"),
    ACTIVE("ACTIVE", 1l, "Active"),
    DELETE("DELETE", 2l, "Delete");

    private String lookupType;
    private Long lookupValue;
    private String description;

    Status(String lookupType, Long lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public static GLookup getStatusByValue(Long lookupValue) {
        Status status = null;
        if (lookupValue == 0l) {
            status = INACTIVE;
        } else if (lookupValue == 1l) {
            status = ACTIVE;
        } else if (lookupValue == 2l) {
            status = DELETE;
        }
        return new GLookup(status.lookupType, status.lookupValue, status.description);
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
