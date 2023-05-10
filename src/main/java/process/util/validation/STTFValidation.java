package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;
import process.util.lookuputil.FormType;
import process.util.lookuputil.IsDefault;

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
    private String formType;

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

    public String getFormType() {
        return formType;
    }

    public void setFormType(String formType) {
        this.formType = formType;
    }

    public void isValidSTTF() {
        try {
            if (this.isNull(this.formName)) {
                this.setErrorMsg(String.format("FormName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.description)) {
                this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.defaultSTTF)) {
                this.setErrorMsg(String.format("Default should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(IsDefault.getDefaultByDescription(this.defaultSTTF))) {
                this.setErrorMsg(String.format("Default type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.formType)) {
                this.setErrorMsg(String.format("FormType should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(FormType.getFormTypeByDescription(this.formType))) {
                this.setErrorMsg(String.format("FormType type not correct at row %s.<br>", rowCounter));
            }
        } catch (Exception ex) {
            this.setErrorMsg(String.format(ex.getLocalizedMessage() + " at row %s.<br>", rowCounter));
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
