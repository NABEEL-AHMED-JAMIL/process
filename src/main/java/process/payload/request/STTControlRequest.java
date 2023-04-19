package process.payload.request;

import com.google.gson.Gson;

public class STTControlRequest {

    private Long sttcId;
    private String controlName;
    private String description;
    private String filedLookUpId;
    private String filedName;
    private String filedNewLine;
    private String filedTitle;
    private String filedType;
    private String filedWidth;
    private Boolean mandatory;
    private Long maxLength;
    private Long minLength;
    private String pattern;
    private String place_holder;
    private Boolean separateLine;
    private Long status;
    private Long  sttcOrder;
    private ParseRequest accessUserDetail;

    public STTControlRequest() {
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
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

    public String getFiledLookUpId() {
        return filedLookUpId;
    }

    public void setFiledLookUpId(String filedLookUpId) {
        this.filedLookUpId = filedLookUpId;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public String getFiledNewLine() {
        return filedNewLine;
    }

    public void setFiledNewLine(String filedNewLine) {
        this.filedNewLine = filedNewLine;
    }

    public String getFiledTitle() {
        return filedTitle;
    }

    public void setFiledTitle(String filedTitle) {
        this.filedTitle = filedTitle;
    }

    public String getFiledType() {
        return filedType;
    }

    public void setFiledType(String filedType) {
        this.filedType = filedType;
    }

    public String getFiledWidth() {
        return filedWidth;
    }

    public void setFiledWidth(String filedWidth) {
        this.filedWidth = filedWidth;
    }

    public Boolean getMandatory() {
        return mandatory;
    }

    public void setMandatory(Boolean mandatory) {
        this.mandatory = mandatory;
    }

    public Long getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Long maxLength) {
        this.maxLength = maxLength;
    }

    public Long getMinLength() {
        return minLength;
    }

    public void setMinLength(Long minLength) {
        this.minLength = minLength;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getPlace_holder() {
        return place_holder;
    }

    public void setPlace_holder(String place_holder) {
        this.place_holder = place_holder;
    }

    public Boolean getSeparateLine() {
        return separateLine;
    }

    public void setSeparateLine(Boolean separateLine) {
        this.separateLine = separateLine;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Long getSttcOrder() {
        return sttcOrder;
    }

    public void setSttcOrder(Long sttcOrder) {
        this.sttcOrder = sttcOrder;
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
