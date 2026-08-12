package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * Configuration for an AI agent that can process a file's content (extracted text) from the
 * Bucket Browser using a configured LLM provider -- apiKey is stored encrypted (see
 * EncryptionUtil) and is never returned to the frontend once saved.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "ai_agent", indexes = {
    @Index(name = "idx_ai_agent_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgent {

    @GenericGenerator(
        name = "aiAgentSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "ai_agent_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "ai_agent_id")
    @GeneratedValue(generator = "aiAgentSequenceGenerator")
    private Long aiAgentId;

    /** Owning tenant -- see Tenant/TenantContext. Nullable during the Phase 0 migration window
     * (backfilled to the Default tenant by TenantSeedService on startup). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    /** TEXT, not a capped varchar -- Hibernate's default varchar(255) silently 500'd (Postgres
     * "value too long") for any description past 255 chars, with no client-side warning to
     * explain why saving just failed. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** "OpenAI", "Anthropic", or "Custom" -- selects which request/response shape processText uses. */
    @Column(name = "provider", nullable = false)
    private String provider;

    /** Only required/used when provider = "Custom" -- built-in providers use their own well-known endpoint. */
    @Column(name = "api_endpoint")
    private String apiEndpoint;

    /** AES-256-GCM ciphertext (see EncryptionUtil) -- never the plain key. */
    @Column(name = "api_key", length = 1000)
    private String apiKey;

    @Column(name = "model", nullable = false)
    private String model;

    /** Comma-separated lowercase file extensions this agent applies to, e.g. "pdf,csv,txt". */
    @Column(name = "target_file_types", nullable = false)
    private String targetFileTypes;

    /** The agent's system prompt / task description (extract fields, summarize, answer questions, etc). */
    @Column(name = "instructions", columnDefinition = "TEXT", nullable = false)
    private String instructions;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    /** Ollama only -- when true, sends "format": "json" so Ollama constrains token sampling to
     * always emit syntactically valid JSON, regardless of how well the underlying model would
     * otherwise follow a "respond with JSON only" instruction (weaker/smaller local models are
     * prone to ignoring that and replying with a prose/markdown summary instead). */
    @Column(name = "json_mode")
    private Boolean jsonMode = false;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    /** Stable public identifier (separate from the internal sequence aiAgentId) -- safe to put
     * in a Source Task XML payload or hand to an external consumer, since it never reveals the
     * apiKey and can't be enumerated/guessed like the sequential id. Backfilled lazily for
     * agents saved before this field existed (see AiAgentServiceImpl#ensureToolUuid). */
    @Column(name = "tool_uuid", unique = true, length = 36)
    private String toolUuid;

    public AiAgent() {}

    public Long getAiAgentId() {
        return aiAgentId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public void setAiAgentId(Long aiAgentId) {
        this.aiAgentId = aiAgentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTargetFileTypes() {
        return targetFileTypes;
    }

    public void setTargetFileTypes(String targetFileTypes) {
        this.targetFileTypes = targetFileTypes;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Boolean getJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(Boolean jsonMode) {
        this.jsonMode = jsonMode;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getToolUuid() {
        return toolUuid;
    }

    public void setToolUuid(String toolUuid) {
        this.toolUuid = toolUuid;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
