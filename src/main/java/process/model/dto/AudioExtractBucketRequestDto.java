package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * Request body for AudioTranscriptRestApi/extractFromBucket -- identifies an audio object
 * already sitting in MinIO to run through the transcript-extraction pipeline.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudioExtractBucketRequestDto {

    private String bucket;
    private String key;
    private Boolean timestamps;

    public AudioExtractBucketRequestDto() {}

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Boolean getTimestamps() {
        return timestamps;
    }

    public void setTimestamps(Boolean timestamps) {
        this.timestamps = timestamps;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
