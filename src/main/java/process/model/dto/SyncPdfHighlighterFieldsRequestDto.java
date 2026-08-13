package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.gson.Gson;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown=true)
public class SyncPdfHighlighterFieldsRequestDto {

    private Long pdfHighlighterTaskId;
    private List<PdfHighlighterFieldDto> fields;

    public SyncPdfHighlighterFieldsRequestDto() {
    }

    public Long getPdfHighlighterTaskId() {
        return pdfHighlighterTaskId;
    }

    public void setPdfHighlighterTaskId(Long pdfHighlighterTaskId) {
        this.pdfHighlighterTaskId = pdfHighlighterTaskId;
    }

    public List<PdfHighlighterFieldDto> getFields() {
        return fields;
    }

    public void setFields(List<PdfHighlighterFieldDto> fields) {
        this.fields = fields;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
