package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum NOTIFICATION_STATUS {

    UNREAD("UNREAD",0l, "Un Read"),
    READ("READ",1l, "Read");

    private String lookupType;
    private Long lookupValue;
    private String description;

    NOTIFICATION_STATUS(String lookupType, Long lookupValue, String description) {
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

    public static GLookup getStatusByValue(Long lookupValue) {
        NOTIFICATION_STATUS notificationStatus = null;
        if (lookupValue.equals(UNREAD.lookupValue)) {
            notificationStatus = UNREAD;
        } else if (lookupValue.equals(READ.lookupValue)) {
            notificationStatus = READ;
        }
        return new GLookup(notificationStatus.lookupType,
            notificationStatus.lookupValue, notificationStatus.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}