package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;

/**
 * One drawn field region on a page of a PdfHighlighterTask's PDF -- label + bounding box
 * (PDF points, top-left origin), plus an optional text-anchored selector derived client-side
 * from the page's text layer (the PDF equivalent of an XPath).
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "pdf_highlighter_field")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PdfHighlighterField {

    @GenericGenerator(
        name = "pdfHighlighterFieldSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "pdf_highlighter_field_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "pdf_highlighter_field_id", unique = true, nullable = false)
    @GeneratedValue(generator = "pdfHighlighterFieldSequenceGenerator")
    private Long pdfHighlighterFieldId;

    @Column(name = "pdf_highlighter_task_id",
        nullable = false)
    private Long pdfHighlighterTaskId;

    @Column(name = "label",
        nullable = false)
    private String label;

    @Column(name = "page",
        nullable = false)
    private Integer page;

    @Column(name = "x", nullable = false)
    private Double x;

    @Column(name = "y", nullable = false)
    private Double y;

    @Column(name = "width", nullable = false)
    private Double width;

    @Column(name = "height", nullable = false)
    private Double height;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "selector_path")
    private String selectorPath;

    @Column(name = "selector_text",
        columnDefinition = "text")
    private String selectorText;

    @Column(name = "selector_prefix")
    private String selectorPrefix;

    @Column(name = "selector_suffix")
    private String selectorSuffix;

    @Column(name = "use_xpath_first",
        nullable = false,
        columnDefinition = "boolean not null default false")
    private Boolean useXpathFirst = false;

    public PdfHighlighterField() { }

    public Long getPdfHighlighterFieldId() {
        return pdfHighlighterFieldId;
    }

    public void setPdfHighlighterFieldId(Long pdfHighlighterFieldId) {
        this.pdfHighlighterFieldId = pdfHighlighterFieldId;
    }

    public Long getPdfHighlighterTaskId() {
        return pdfHighlighterTaskId;
    }

    public void setPdfHighlighterTaskId(Long pdfHighlighterTaskId) {
        this.pdfHighlighterTaskId = pdfHighlighterTaskId;
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
