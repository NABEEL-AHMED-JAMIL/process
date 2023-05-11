package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;
import process.util.lookuputil.FormControlType;
import process.util.lookuputil.IsDefault;
import java.util.regex.Pattern;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTCValidation {

    private Pattern patternRegx = Pattern.compile("^[0-9]+$");

    private Integer rowCounter = 0;
    private String errorMsg;

    private String controlOrder;
    private String controlName;
    private String description;
    private String filedName;
    private String filedTitle;
    private String filedWidth;
    private String placeHolder;
    private String pattern;
    private String filedType;
    private String filedLkValue;
    private String minLength;
    private String maxLength;
    private String required;

    public STTCValidation() {}

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

    public String getControlOrder() {
        return controlOrder;
    }

    public void setControlOrder(String controlOrder) {
        this.controlOrder = controlOrder;
    }

    public String getControlName() {
        return controlName;
    }

    public void setControlName(String controlName) {
        this.controlName = controlName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public String getFiledTitle() {
        return filedTitle;
    }

    public void setFiledTitle(String filedTitle) {
        this.filedTitle = filedTitle;
    }

    public String getFiledWidth() {
        return filedWidth;
    }

    public void setFiledWidth(String filedWidth) {
        this.filedWidth = filedWidth;
    }

    public String getPlaceHolder() {
        return placeHolder;
    }

    public void setPlaceHolder(String placeHolder) {
        this.placeHolder = placeHolder;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getFiledType() {
        return filedType;
    }

    public void setFiledType(String filedType) {
        this.filedType = filedType;
    }

    public String getFiledLkValue() {
        return filedLkValue;
    }

    public void setFiledLkValue(String filedLkValue) {
        this.filedLkValue = filedLkValue;
    }

    public String getMinLength() {
        return minLength;
    }

    public void setMinLength(String minLength) {
        this.minLength = minLength;
    }

    public String getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(String maxLength) {
        this.maxLength = maxLength;
    }

    public String getRequired() {
        return required;
    }

    public void setRequired(String required) {
        this.required = required;
    }

    public void isValidSTTC() {
        try {
            if (this.isNull(this.controlOrder)) {
                this.setErrorMsg(String.format("ControlOrder should not be empty at row %s.<br>", rowCounter));
            } else if (!this.patternRegx.matcher(controlOrder).matches()) {
                this.setErrorMsg(String.format("ControlOrder type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.controlName)) {
                this.setErrorMsg(String.format("ControlName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.description)) {
                this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.filedName)) {
                this.setErrorMsg(String.format("FiledName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.filedTitle)) {
                this.setErrorMsg(String.format("FiledTitle should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.filedWidth)) {
                this.setErrorMsg(String.format("FiledWidth should not be empty at row %s.<br>", rowCounter));
            } else if (!this.patternRegx.matcher(filedWidth).matches()) {
                this.setErrorMsg(String.format("FiledWidth type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.filedType)) {
                this.setErrorMsg(String.format("FiledType should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(FormControlType.getFormControlTypeByDescription(this.filedType))) {
                this.setErrorMsg(String.format("FiledType type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.required)) {
                this.setErrorMsg(String.format("Required should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(IsDefault.getDefaultByDescription(this.required))) {
                this.setErrorMsg(String.format("Required type not correct at row %s.<br>", rowCounter));
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
