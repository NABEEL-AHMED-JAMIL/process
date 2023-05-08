package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTFValidation {

    private Integer rowCounter = 0;
    private String errorMsg;

    private String formName;
    private String description;
    private String defaultSTTF;

    public STTFValidation() {}

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

    public String getFormName() {
        return formName;
    }

    public void setFormName(String formName) {
        this.formName = formName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultSTTF() {
        return defaultSTTF;
    }

    public void setDefaultSTTF(String defaultSTTF) {
        this.defaultSTTF = defaultSTTF;
    }

    public void isValidSTTF() {
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
