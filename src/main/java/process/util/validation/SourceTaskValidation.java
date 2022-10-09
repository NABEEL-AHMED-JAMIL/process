package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * This SourceTaskValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskValidation {

    private Integer rowCounter = 0;
    private String sourceTaskTypeId;
    private String taskName;
    private String taskPayload;
    private String errorMsg;

    public SourceTaskValidation() {}

    public SourceTaskValidation(String sourceTaskTypeId, String taskName, String taskPayload) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.taskName = taskName;
        this.taskPayload = taskPayload;
    }

    public Integer getRowCounter() {
        return rowCounter;
    }

    public void setRowCounter(Integer rowCounter) {
        this.rowCounter = rowCounter;
    }

    public String getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(String sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTaskPayload() {
        return taskPayload;
    }

    public void setTaskPayload(String taskPayload) {
        this.taskPayload = taskPayload;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        if (isNull(this.errorMsg)) {
            this.errorMsg = errorMsg;
        } else {
            this.errorMsg += errorMsg;
        }
    }

    /**
     * This isValidJobDetail use to validate the
     * job detail of the job valid return true
     * if non-valid return false
     * @return boolean true|false
     * */
    public void isValidSourceTask() {
        if (this.isNull(this.sourceTaskTypeId)) {
            this.setErrorMsg(String.format("TaskTypeId should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.taskName)) {
            this.setErrorMsg(String.format("Task Name should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.taskPayload)) {
            this.setErrorMsg(String.format("Task Payload should not be empty at row %s.<br>", rowCounter));
        }
    }

    private static boolean isNull(String filed) {
        return (filed == null || filed.length() == 0) ? true : false;
    }

        @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
