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

    private Long sttCId;
    private Long sttCOrder;
    private String sttCName;
    private String description;
    private String filedType;
    private String filedTitle;
    private String filedName;
    private String placeHolder;
    private Long filedWidth;
    private Long minLength;
    private Long maxLength;
    private Long filedLookUp;
    private boolean separateLine;
    private boolean mandatory;
    private boolean defaultSttC;
    private String pattern;
    private Long status;
    private boolean filedNewLine;
    private ParseRequest accessUserDetail;

    public STTControlRequest() {
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

    public String getFiledType() {
        return filedType;
    }

    public void setFiledType(String filedType) {
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

    public Long getFiledLookUp() {
        return filedLookUp;
    }

    public void setFiledLookUp(Long filedLookUp) {
        this.filedLookUp = filedLookUp;
    }

    public boolean isSeparateLine() {
        return separateLine;
    }

    public void setSeparateLine(boolean separateLine) {
        this.separateLine = separateLine;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public boolean isDefaultSttC() {
        return defaultSttC;
    }

    public void setDefaultSttC(boolean defaultSttC) {
        this.defaultSttC = defaultSttC;
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

    public boolean isFiledNewLine() {
        return filedNewLine;
    }

    public void setFiledNewLine(boolean filedNewLine) {
        this.filedNewLine = filedNewLine;
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
