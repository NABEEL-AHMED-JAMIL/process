package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum NOTIFICATION_TYPE {

    USER_NOTIFICATION("USER_NOTIFICATION",0l, "User Notification"),
    OTHER_NOTIFICATION("OTHER_NOTIFICATION",1l,"Other Notification");

    private String lookupType;
    private Long lookupValue;
    private String description;

    NOTIFICATION_TYPE(String lookupType, Long lookupValue, String description) {
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
        NOTIFICATION_TYPE notificationType = null;
        if (lookupValue.equals(USER_NOTIFICATION.lookupValue)) {
            notificationType = USER_NOTIFICATION;
        } else if (lookupValue.equals(OTHER_NOTIFICATION.lookupValue)) {
            notificationType = OTHER_NOTIFICATION;
        }
        return new GLookup(notificationType.lookupType, notificationType.lookupValue, notificationType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}