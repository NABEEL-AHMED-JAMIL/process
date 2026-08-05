package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * Request body for AudioTranscriptRestApi/extractFromYoutube -- a YouTube link to download
 * audio from and run through the transcript-extraction pipeline.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YoutubeExtractRequestDto {

    private String url;
    private Boolean timestamps;

    public YoutubeExtractRequestDto() {}

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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
