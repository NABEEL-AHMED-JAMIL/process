package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTControlResponse {

    private Long sttcId;
    private String sttcName;
    private String description;
    private GLookup fieldType;
    private String fieldTitle;
    private String fieldName;
    private String placeHolder;
    private Long fieldWidth;
    private Long minLength;
    private Long maxLength;
    private String fieldLookUp;
    private GLookup mandatory;
    private GLookup sttcDefault;
    private GLookup sttcDisabled;
    private String defaultValue;
    private String pattern;
    private GLookup status;
    private Long controlOrder;
    private STTCInteractionsResponse interaction;

    public STTControlResponse() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public GLookup getFieldType() {
        return fieldType;
    }

    public void setFieldType(GLookup fieldType) {
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

    public GLookup getMandatory() {
        return mandatory;
    }

    public void setMandatory(GLookup mandatory) {
        this.mandatory = mandatory;
    }

    public GLookup getSttcDefault() {
        return sttcDefault;
    }

    public void setSttcDefault(GLookup sttcDefault) {
        this.sttcDefault = sttcDefault;
    }

    public GLookup getSttcDisabled() {
        return sttcDisabled;
    }

    public void setSttcDisabled(GLookup sttcDisabled) {
        this.sttcDisabled = sttcDisabled;
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

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public Long getControlOrder() {
        return controlOrder;
    }

    public void setControlOrder(Long controlOrder) {
        this.controlOrder = controlOrder;
    }

    public STTCInteractionsResponse getInteraction() {
        return interaction;
    }

    public void setInteraction(STTCInteractionsResponse interaction) {
        this.interaction = interaction;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
