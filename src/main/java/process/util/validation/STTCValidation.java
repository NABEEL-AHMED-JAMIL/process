package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTCValidation {

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
