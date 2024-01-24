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

    private Long sectionOder;
    private STTSectionResponse section;
    private List<STTControlResponse> controlFiled;

    public SectionControlResponse() {
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

    public List<STTControlResponse> getControlFiled() {
        return controlFiled;
    }

    public void setControlFiled(List<STTControlResponse> controlFiled) {
        this.controlFiled = controlFiled;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
