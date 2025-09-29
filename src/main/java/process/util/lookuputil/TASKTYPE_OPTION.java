package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum TASKTYPE_OPTION {

    API("API", 0l, "Api"),
    AWS_SQS("AWS_SQS", 1l, "SQS"),
    WEB_SOCKET("WEB_SOCKET", 2l, "WS"),
    KAFKA("KAFKA", 3l, "Kafka");

    private String lookupType;
    private Long lookupValue;
    private String description;

    TASKTYPE_OPTION(String lookupType, Long lookupValue, String description) {
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

    public static GLookup getTaskTypeByValue(Long lookupValue) {
        TASKTYPE_OPTION tasktypeOption = null;
        if (lookupValue.equals(API.getLookupValue())) {
            tasktypeOption = API;
        } else if (lookupValue.equals(AWS_SQS.getLookupValue())) {
            tasktypeOption = AWS_SQS;
        } else if (lookupValue.equals(WEB_SOCKET.getLookupValue())) {
            tasktypeOption = WEB_SOCKET;
        } else if (lookupValue.equals(KAFKA.getLookupValue())) {
            tasktypeOption = KAFKA;
        }
        return new GLookup(tasktypeOption.lookupType, tasktypeOption.lookupValue, tasktypeOption.description);
    }

    public static GLookup getTaskTypeByDescription(String description) {
        TASKTYPE_OPTION tasktypeOption = null;
        if (description.equals(API.getDescription())) {
            tasktypeOption = API;
        } else if (description.equals(AWS_SQS.getDescription())) {
            tasktypeOption = AWS_SQS;
        } else if (description.equals(WEB_SOCKET.getDescription())) {
            tasktypeOption = WEB_SOCKET;
        } else if (description.equals(KAFKA.getDescription())) {
            tasktypeOption = KAFKA;
        }
        return new GLookup(tasktypeOption.lookupType, tasktypeOption.lookupValue, tasktypeOption.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
