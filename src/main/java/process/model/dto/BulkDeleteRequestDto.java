package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.gson.Gson;
import java.util.List;

/**
 * DTO for a bulk-delete request from the Bucket Browser's multi-select action
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BulkDeleteRequestDto {

    private String bucket;
    private List<String> keys;

    public BulkDeleteRequestDto() {}

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
