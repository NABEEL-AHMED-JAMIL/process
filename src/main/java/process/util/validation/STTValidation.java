package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;
import process.util.lookuputil.ISDEFAULT;
import process.util.lookuputil.TASKTYPE_OPTION;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTValidation {

    private Integer rowCounter = 0;
    private String errorMsg;

    private String serviceName;
    private String description;
    private String defaultSTT;
    private String taskType;
    private String topicName;
    private String partitions;
    private String topicPattern = "topic=%s&partitions=[*]";

    public STTValidation() {}

    public Integer getRowCounter() {
        return rowCounter;
    }

    public void setRowCounter(Integer rowCounter) {
        this.rowCounter = rowCounter;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultSTT() {
        return defaultSTT;
    }

    public void setDefaultSTT(String defaultSTT) {
        this.defaultSTT = defaultSTT;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public String getPartitions() {
        return partitions;
    }

    public void setPartitions(String partitions) {
        this.partitions = partitions;
    }

    public String getTopicPattern() {
        return topicPattern;
    }

    public void setTopicPattern(String topicPattern) {
        this.topicPattern = topicPattern;
    }

    public void isValidSTT() {
        try {
            if (this.isNull(this.serviceName)) {
                this.setErrorMsg(String.format("ServiceName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.description)) {
                this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.defaultSTT)) {
                this.setErrorMsg(String.format("Default should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(ISDEFAULT.getDefaultByDescription(this.defaultSTT))) {
                this.setErrorMsg(String.format("Default type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.taskType)) {
                this.setErrorMsg(String.format("TaskType should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(TASKTYPE_OPTION.getTaskTypeByDescription(this.taskType))) {
                this.setErrorMsg(String.format("TaskType type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.topicName)) {
                this.setErrorMsg(String.format("TopicName should not be empty at row %s.<br>", rowCounter));
            } else {
                this.topicPattern = String.format(this.topicPattern, this.topicName);
            }
            if (this.isNull(this.partitions)) {
                this.setErrorMsg(String.format("Partitions should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.topicPattern)) {
                this.setErrorMsg(String.format("TopicPattern should not be empty at row %s.<br>", rowCounter));
            }
        } catch (Exception ex) {
            this.setErrorMsg(String.format(ex.getLocalizedMessage() + " at row %s.<br>", rowCounter));
        }
    }

    private static boolean isNull(String field) {
        return (field == null || field.length() == 0) ? true : false;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
