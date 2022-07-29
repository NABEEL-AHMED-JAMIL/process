package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettingDto {

    private List<LookupDataDto> lookupDatas;
    private List<SourceTaskTypeDto> sourceTaskTypes;

    public SettingDto() {}

    public List<LookupDataDto> getLookupDatas() {
        return lookupDatas;
    }

    public void setLookupDatas(List<LookupDataDto> lookupDatas) {
        this.lookupDatas = lookupDatas;
    }

    public List<SourceTaskTypeDto> getSourceTaskTypes() {
        return sourceTaskTypes;
    }

    public void setSourceTaskTypes(List<SourceTaskTypeDto> sourceTaskTypes) {
        this.sourceTaskTypes = sourceTaskTypes;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
