package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PdfHighlighterFieldDto {

    private Long pdfHighlighterFieldId;
    private String label;
    private Integer page;
    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private Integer displayOrder;
    private String selectorPath;
    private String selectorText;
    private String selectorPrefix;
    private String selectorSuffix;
    private Boolean useXpathFirst;

    public PdfHighlighterFieldDto() {
    }

    public Long getPdfHighlighterFieldId() {
        return pdfHighlighterFieldId;
    }

    public void setPdfHighlighterFieldId(Long pdfHighlighterFieldId) {
        this.pdfHighlighterFieldId = pdfHighlighterFieldId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getSelectorPath() {
        return selectorPath;
    }

    public void setSelectorPath(String selectorPath) {
        this.selectorPath = selectorPath;
    }

    public String getSelectorText() {
        return selectorText;
    }

    public void setSelectorText(String selectorText) {
        this.selectorText = selectorText;
    }

    public String getSelectorPrefix() {
        return selectorPrefix;
    }

    public void setSelectorPrefix(String selectorPrefix) {
        this.selectorPrefix = selectorPrefix;
    }

    public String getSelectorSuffix() {
        return selectorSuffix;
    }

    public void setSelectorSuffix(String selectorSuffix) {
        this.selectorSuffix = selectorSuffix;
    }

    public Boolean getUseXpathFirst() {
        return useXpathFirst;
    }

    public void setUseXpathFirst(Boolean useXpathFirst) {
        this.useXpathFirst = useXpathFirst;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
