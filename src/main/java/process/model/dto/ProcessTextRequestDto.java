package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * Request body for AiAgentRestApi/processText -- the frontend has already extracted the
 * file's text (via pdf.js for PDFs, or a raw fetch for text-based types) before calling this,
 * so the backend never needs its own PDF-parsing dependency.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessTextRequestDto {

    private Long aiAgentId;
    /** Alternative to aiAgentId -- looked up via AiAgent#toolUuid instead of the internal
     * sequence id. Used by external consumers (e.g. a Source Task's XML "tool url") that only
     * ever see the public toolUuid, never the internal id. Either field resolves to the same
     * agent; aiAgentId wins if both are supplied. */
    private String aiAgentUuid;
    private String fileName;
    private String text;
    /** Optional -- when supplied, used in place of the saved agent's own instructions for
     * this call only (nothing is persisted). Lets a caller reuse an agent's provider/model/
     * apiKey while typing a one-off prompt, e.g. the Audio Transcript Extractor screen. */
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
