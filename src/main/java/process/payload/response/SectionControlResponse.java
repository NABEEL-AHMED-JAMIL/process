package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SectionControlResponse {

    private Long auSttsId;
    private Long sectionOder;
    private STTSectionResponse section;
    private List<STTControlResponse> controlField;

    public SectionControlResponse() {
    }

    public Long getAuSttsId() {
        return auSttsId;
    }

    public void setAuSttsId(Long auSttsId) {
        this.auSttsId = auSttsId;
    }

    public Long getSectionOder() {
        return sectionOder;
    }

    public void setSectionOder(Long sectionOder) {
        this.sectionOder = sectionOder;
    }

    public STTSectionResponse getSection() {
        return section;
    }

    public void setSection(STTSectionResponse section) {
        this.section = section;
    }

    public List<STTControlResponse> getControlField() {
        return controlField;
    }

    public void setControlField(List<STTControlResponse> controlField) {
        this.controlField = controlField;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
