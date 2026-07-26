package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * DTO for a bucket entry available in the Bucket Browser dropdown, sourced from the
 * BUCKET_LIST lookup: lookupType is the display label, lookupValue is the real bucket
 * name, description is the storage provider (MINIO/S3/AZURE).
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BucketSummaryDto {

    private String label;
    private String bucket;
    private String provider;

    public BucketSummaryDto() {}

    public BucketSummaryDto(String label, String bucket, String provider) {
        this.label = label;
        this.bucket = bucket;
        this.provider = provider;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
