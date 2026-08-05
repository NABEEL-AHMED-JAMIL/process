package process.model.dto;

/**
 * DTO use to represent a single model pulled into the local Ollama container (mirrors the
 * shape of one entry in Ollama's GET /api/tags response).
 * @author Nabeel Ahmed
 */
public class OllamaModelDto {

    private String name;
    private Long size;
    private String modifiedAt;
    private String digest;
    private String family;
    private String parameterSize;
    private String quantizationLevel;

    public OllamaModelDto() { }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getParameterSize() {
        return parameterSize;
    }

    public void setParameterSize(String parameterSize) {
        this.parameterSize = parameterSize;
    }

    public String getQuantizationLevel() {
        return quantizationLevel;
    }

    public void setQuantizationLevel(String quantizationLevel) {
        this.quantizationLevel = quantizationLevel;
    }
}
