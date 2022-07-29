package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class XmlMakerRequest {

    private String taskName;
    private String sourceTaskTypeId;
    private List<TagInfo> tagsInfo;

    public XmlMakerRequest() {}



    public XmlMakerRequest(String taskName, String sourceTaskTypeId,
        List<TagInfo> tagsInfo) {
        this.taskName = taskName;
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.tagsInfo = tagsInfo;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(String sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public List<TagInfo> getTagsInfo() {
        return tagsInfo;
    }

    public void setTagsInfo(List<TagInfo> tagsInfo) {
        this.tagsInfo = tagsInfo;
    }

    public static class TagInfo {

        private String tagKey;
        private String tagParent;
        private String tagValue;

        public TagInfo() { }

        public TagInfo(String tagKey, String tagParent, String tagValue) {
            this.tagKey = tagKey;
            this.tagParent = tagParent;
            this.tagValue = tagValue;
        }

        public String getTagKey() {
            return tagKey;
        }

        public void setTagKey(String tagKey) {
            this.tagKey = tagKey;
        }

        public String getTagParent() {
            return tagParent;
        }

        public void setTagParent(String tagParent) {
            this.tagParent = tagParent;
        }

        public String getTagValue() {
            return tagValue;
        }

        public void setTagValue(String tagValue) {
            this.tagValue = tagValue;
        }

        @Override
        public String toString() { return new Gson().toJson(this); }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
