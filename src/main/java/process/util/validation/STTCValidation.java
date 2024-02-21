package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;
import process.util.lookuputil.FORM_CONTROL_TYPE;
import process.util.lookuputil.ISDEFAULT;
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
    private String controlName;
    private String description;
    private String fieldName;
    private String fieldTitle;
    private String fieldWidth;
    private String placeHolder;
    private String pattern;
    private String fieldType;
    private String fieldLkValue;
    private String minLength;
    private String maxLength;
    private String required;

    public STTCValidation() {}

    public Pattern getPatternRegx() {
        return patternRegx;
    }

    public void setPatternRegx(Pattern patternRegx) {
        this.patternRegx = patternRegx;
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

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldTitle() {
        return fieldTitle;
    }

    public void setFieldTitle(String fieldTitle) {
        this.fieldTitle = fieldTitle;
    }

    public String getFieldWidth() {
        return fieldWidth;
    }

    public void setFieldWidth(String fieldWidth) {
        this.fieldWidth = fieldWidth;
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

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getFieldLkValue() {
        return fieldLkValue;
    }

    public void setFieldLkValue(String fieldLkValue) {
        this.fieldLkValue = fieldLkValue;
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
            if (this.isNull(this.controlName)) {
                this.setErrorMsg(String.format("ControlName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.description)) {
                this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.fieldName)) {
                this.setErrorMsg(String.format("FieldName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.fieldTitle)) {
                this.setErrorMsg(String.format("FieldTitle should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.fieldWidth)) {
                this.setErrorMsg(String.format("FieldWidth should not be empty at row %s.<br>", rowCounter));
            } else if (!this.patternRegx.matcher(fieldWidth).matches()) {
                this.setErrorMsg(String.format("FieldWidth type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.fieldType)) {
                this.setErrorMsg(String.format("FieldType should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(FORM_CONTROL_TYPE.getFormControlTypeByDescription(this.fieldType))) {
                this.setErrorMsg(String.format("FieldType type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.required)) {
                this.setErrorMsg(String.format("Required should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(ISDEFAULT.getDefaultByDescription(this.required))) {
                this.setErrorMsg(String.format("Required type not correct at row %s.<br>", rowCounter));
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
