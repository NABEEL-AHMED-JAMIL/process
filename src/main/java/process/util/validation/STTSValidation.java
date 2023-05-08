package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSValidation {

    private Integer rowCounter = 0;
    private String errorMsg;

    private String sectionOrder;
    private String sectionName;
    private String description;
    private String defaultSTTS;

    public STTSValidation() {
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
        this.errorMsg = errorMsg;
    }

    public String getSectionOrder() {
        return sectionOrder;
    }

    public void setSectionOrder(String sectionOrder) {
        this.sectionOrder = sectionOrder;
    }

    public String getSectionName() {
        return sectionName;
    }

    public void setSectionName(String sectionName) {
        this.sectionName = sectionName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultSTTS() {
        return defaultSTTS;
    }

    public void setDefaultSTTS(String defaultSTTS) {
        this.defaultSTTS = defaultSTTS;
    }

    public void isValidSTTS() {
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

    private static boolean isNull(String filed) {
        return (filed == null || filed.length() == 0) ? true : false;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
