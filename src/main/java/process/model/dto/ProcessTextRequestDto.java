package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessTextRequestDto {

    private Long aiAgentId;

    private String aiAgentUuid;
    private String fileName;
    private String text;

    private String instructions;

    public ProcessTextRequestDto() {}

    public Long getAiAgentId() {
        return aiAgentId;
    }

    public void setAiAgentId(Long aiAgentId) {
        this.aiAgentId = aiAgentId;
    }

    public String getAiAgentUuid() {
        return aiAgentUuid;
    }

    public void setAiAgentUuid(String aiAgentUuid) {
        this.aiAgentUuid = aiAgentUuid;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
