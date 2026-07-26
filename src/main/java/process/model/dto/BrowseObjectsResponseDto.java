package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.List;

/**
 * DTO bundling a page of bucket-listing results with the token to fetch the next page
 * (scroll-based pagination) -- null when there are no more objects.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrowseObjectsResponseDto {

    private List<ObjectSummaryDto> objects;
    private String nextContinuationToken;

    public BrowseObjectsResponseDto() {}

    public BrowseObjectsResponseDto(List<ObjectSummaryDto> objects, String nextContinuationToken) {
        this.objects = objects;
        this.nextContinuationToken = nextContinuationToken;
    }

    public List<ObjectSummaryDto> getObjects() {
        return objects;
    }

    public void setObjects(List<ObjectSummaryDto> objects) {
        this.objects = objects;
    }

    public String getNextContinuationToken() {
        return nextContinuationToken;
    }

    public void setNextContinuationToken(String nextContinuationToken) {
        this.nextContinuationToken = nextContinuationToken;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
