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
        if (lookupValue == 0l) {
            taskType = API;
        } else if (lookupValue == 1l) {
            taskType = AWS_SQS;
        } else if (lookupValue == 2l) {
            taskType = WEB_SOCKET;
        } else if (lookupValue == 3l) {
            taskType = KAFKA;
        }
        return new GLookup(taskType.lookupType,
            taskType.lookupValue, taskType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
