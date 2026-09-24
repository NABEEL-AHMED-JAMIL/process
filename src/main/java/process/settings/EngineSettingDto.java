package process.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One engine setting on the platform admin's screen (MIG-167). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EngineSettingDto {

    private String key;
    private String value;
    private String description;
    private boolean editable;
    private String updatedAt;
    private String updatedByName;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEditable() { return editable; }
    public void setEditable(boolean editable) { this.editable = editable; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
