package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;

/**
 * This SourceTaskValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupValidation {

    // validation field
    private final String REGEX = "^[-a-zA-Z0-9@\\.+_]+$";
    private Integer rowCounter = 0;
    private String errorMsg;
    // business field
    private String lookupType;
    private String lookupValue;
    private String description;

    public LookupValidation() {
    }

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
        if (ProcessUtil.isNull(this.errorMsg)) {
            this.errorMsg = errorMsg;
        } else {
            this.errorMsg += errorMsg;
        }
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public String getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(String lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * This isValidJobDetail use to validate the
     * job detail of the job valid return true
     * if non-valid return false
     * @return boolean true|false
     * */
    public void isValidLookup() {
        if (this.isNull(this.lookupType)) {
            this.setErrorMsg(String.format("LookupType should not be empty at row %s.<br>", rowCounter));
        } else if (!this.lookupType.matches(this.REGEX)) {
            this.setErrorMsg(String.format("LookupType should not be non space latter at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.lookupValue)) {
            this.setErrorMsg(String.format("LookupValue should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.description)) {
            this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
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
