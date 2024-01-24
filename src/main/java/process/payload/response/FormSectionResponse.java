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
public class FormSectionResponse {

    private List<SectionControlResponse> formSection;

    public FormSectionResponse() {
    }

    public List<SectionControlResponse> getFormSection() {
        return formSection;
    }

    public void setFormSection(List<SectionControlResponse> formSection) {
        this.formSection = formSection;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
