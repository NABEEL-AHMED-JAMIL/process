package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum TaskType {

    API("API", 0l, "Api"),
    AWS_SQS("AWS_SQS", 1l, "SQS"),
    WEB_SOCKET("WEB_SOCKET", 2l, "WS"),
    KAFKA("KAFKA", 3l, "Kafka");

    private String lookupType;
    private Long lookupValue;
    private String description;

    TaskType(String lookupType, Long lookupValue, String description) {
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
        TaskType taskType = null;
        if (lookupValue.equals(API.getLookupValue())) {
            taskType = API;
        } else if (lookupValue.equals(AWS_SQS.getLookupValue())) {
            taskType = AWS_SQS;
        } else if (lookupValue.equals(WEB_SOCKET.getLookupValue())) {
            taskType = WEB_SOCKET;
        } else if (lookupValue.equals(KAFKA.getLookupValue())) {
            taskType = KAFKA;
        }
        return new GLookup(taskType.lookupType, taskType.lookupValue, taskType.description);
    }

    public static GLookup getTaskTypeByDescription(String description) {
        TaskType taskType = null;
        if (description.equals(API.getDescription())) {
            taskType = API;
        } else if (description.equals(AWS_SQS.getDescription())) {
            taskType = AWS_SQS;
        } else if (description.equals(WEB_SOCKET.getDescription())) {
            taskType = WEB_SOCKET;
        } else if (description.equals(KAFKA.getDescription())) {
            taskType = KAFKA;
        }
        return new GLookup(taskType.lookupType, taskType.lookupValue, taskType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
