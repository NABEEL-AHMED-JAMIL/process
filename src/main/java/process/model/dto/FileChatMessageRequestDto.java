package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileChatMessageRequestDto {

    private String bucket;
    private String key;
    private String model;
    private String message;
    private List<FileChatHistoryItemDto> history;

    public FileChatMessageRequestDto() {}

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<FileChatHistoryItemDto> getHistory() {
        return history;
    }

    public void setHistory(List<FileChatHistoryItemDto> history) {
        this.history = history;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
