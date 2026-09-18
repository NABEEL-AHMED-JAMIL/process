package process.model.pojo;

import javax.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;

/** One saved version of a prompt -- what a run pinned to that version actually sent. */
@Entity
@Table(name = "ai_prompt_version")
@IdClass(AiPromptVersion.Key.class)
public class AiPromptVersion {

    public static class Key implements Serializable {
        private Long promptId;
        private Integer version;
        public Key() {}
        public Key(Long promptId, Integer version) { this.promptId = promptId; this.version = version; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return Objects.equals(promptId, k.promptId) && Objects.equals(version, k.version);
        }
        @Override public int hashCode() { return Objects.hash(promptId, version); }
    }

    @Id @Column(name = "prompt_id") private Long promptId;
    @Id @Column(name = "version") private Integer version;
    @Column(name = "connection_id") private Long connectionId;
    @Column(name = "model") private String model;
    @Column(name = "system_instructions", columnDefinition = "TEXT") private String systemInstructions;
    @Column(name = "user_template", nullable = false, columnDefinition = "TEXT") private String userTemplate;
    @Column(name = "variables", nullable = false, columnDefinition = "TEXT") private String variables;
    @Column(name = "output_mode", nullable = false) private String outputMode;
    @Column(name = "output_schema", columnDefinition = "TEXT") private String outputSchema;
    @Column(name = "temperature") private Double temperature;
    @Column(name = "max_tokens") private Integer maxTokens;
    @Column(name = "date_created") private Timestamp dateCreated;
    @Column(name = "created_by") private Long createdBy;

    /** A snapshot of the prompt as it is now. */
    public static AiPromptVersion of(AiPrompt p, Long actor) {
        AiPromptVersion v = new AiPromptVersion();
        v.promptId = p.getPromptId(); v.version = p.getVersion(); v.connectionId = p.getConnectionId(); v.model = p.getModel();
        v.systemInstructions = p.getSystemInstructions(); v.userTemplate = p.getUserTemplate(); v.variables = p.getVariables();
        v.outputMode = p.getOutputMode(); v.outputSchema = p.getOutputSchema(); v.temperature = p.getTemperature(); v.maxTokens = p.getMaxTokens();
        v.dateCreated = new Timestamp(System.currentTimeMillis()); v.createdBy = actor;
        return v;
    }

    public Long getPromptId() { return promptId; }
    public Integer getVersion() { return version; }
    public Long getConnectionId() { return connectionId; }
    public String getModel() { return model; }
    public String getSystemInstructions() { return systemInstructions; }
    public String getUserTemplate() { return userTemplate; }
    public String getVariables() { return variables; }
    public String getOutputMode() { return outputMode; }
    public String getOutputSchema() { return outputSchema; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public Timestamp getDateCreated() { return dateCreated; }
    public Long getCreatedBy() { return createdBy; }
}
