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

    private Long sttCId;
    private Long sttCOrder;
    private String sttCName;
    private String description;
    private GLookup filedType;
    private String filedTitle;
    private String filedName;
    private String placeHolder;
    private Long filedWidth;
    private Long minLength;
    private Long maxLength;
    private String filedLookUp;
    private GLookup mandatory;
    private GLookup defaultSttc;
    private String pattern;
    private GLookup status;

    public STTControlResponse() {
    }

    public Long getSttCId() {
        return sttCId;
    }

    public void setSttCId(Long sttCId) {
        this.sttCId = sttCId;
    }

    public Long getSttCOrder() {
        return sttCOrder;
    }

    public void setSttCOrder(Long sttCOrder) {
        this.sttCOrder = sttCOrder;
    }

    public String getSttCName() {
        return sttCName;
    }

    public void setSttCName(String sttCName) {
        this.sttCName = sttCName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public GLookup getFiledType() {
        return filedType;
    }

    public void setFiledType(GLookup filedType) {
        this.filedType = filedType;
    }

    public String getFiledTitle() {
        return filedTitle;
    }

    public void setFiledTitle(String filedTitle) {
        this.filedTitle = filedTitle;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public String getPlaceHolder() {
        return placeHolder;
    }

    public void setPlaceHolder(String placeHolder) {
        this.placeHolder = placeHolder;
    }

    public Long getFiledWidth() {
        return filedWidth;
    }

    public void setFiledWidth(Long filedWidth) {
        this.filedWidth = filedWidth;
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

    public String getFiledLookUp() {
        return filedLookUp;
    }

    public void setFiledLookUp(String filedLookUp) {
        this.filedLookUp = filedLookUp;
    }

    public GLookup getMandatory() {
        return mandatory;
    }

    public void setMandatory(GLookup mandatory) {
        this.mandatory = mandatory;
    }

    public GLookup getDefaultSttc() {
        return defaultSttc;
    }

    public void setDefaultSttc(GLookup defaultSttc) {
        this.defaultSttc = defaultSttc;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
