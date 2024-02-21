package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTControlRequest {

    private Long sttcId;
    private String sttcName; // yes
    private String fieldType; // yes
    private String fieldTitle; // yes
    private String fieldName; // yes
    private String description; // yes
    private String placeHolder;
    private Long fieldWidth; // 1-12 yes
    private Long minLength; // 1
    private Long maxLength; // not -1
    private String fieldLookUp;
    private boolean mandatory; // yes
    private boolean sttcDisabled;
    private boolean sttcDefault; // yes
    private String defaultValue;
    private String pattern;
    private Long status;
    private ParseRequest accessUserDetail;

    public STTControlRequest() {
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public String getSttcName() {
        return sttcName;
    }

    public void setSttcName(String sttcName) {
        this.sttcName = sttcName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getFieldTitle() {
        return fieldTitle;
    }

    public void setFieldTitle(String fieldTitle) {
        this.fieldTitle = fieldTitle;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPlaceHolder() {
        return placeHolder;
    }

    public void setPlaceHolder(String placeHolder) {
        this.placeHolder = placeHolder;
    }

    public Long getFieldWidth() {
        return fieldWidth;
    }

    public void setFieldWidth(Long fieldWidth) {
        this.fieldWidth = fieldWidth;
    }

    public Long getMinLength() {
        return minLength;
    }

    public void setMinLength(Long minLength) {
        this.minLength = minLength;
    }

    public Long getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Long maxLength) {
        this.maxLength = maxLength;
    }

    public String getFieldLookUp() {
        return fieldLookUp;
    }

    public void setFieldLookUp(String fieldLookUp) {
        this.fieldLookUp = fieldLookUp;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public boolean isSttcDisabled() {
        return sttcDisabled;
    }

    public void setSttcDisabled(boolean sttcDisabled) {
        this.sttcDisabled = sttcDisabled;
    }

    public boolean isSttcDefault() {
        return sttcDefault;
    }

    public void setSttcDefault(boolean sttcDefault) {
        this.sttcDefault = sttcDefault;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public ParseRequest getAccessUserDetail() {
        return accessUserDetail;
    }

    public void setAccessUserDetail(ParseRequest accessUserDetail) {
        this.accessUserDetail = accessUserDetail;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
