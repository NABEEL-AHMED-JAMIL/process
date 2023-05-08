package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigurationMakerRequest {

    private List<TagInfo> xmlTagsInfo;
    private List<TagInfo> jsonTagsInfo;

    public ConfigurationMakerRequest() {}

    public List<TagInfo> getXmlTagsInfo() {
        return xmlTagsInfo;
    }

    public void setXmlTagsInfo(List<TagInfo> xmlTagsInfo) {
        this.xmlTagsInfo = xmlTagsInfo;
    }

    public List<TagInfo> getJsonTagsInfo() {
        return jsonTagsInfo;
    }

    public void setJsonTagsInfo(List<TagInfo> jsonTagsInfo) {
        this.jsonTagsInfo = jsonTagsInfo;
    }

    public static class TagInfo implements Comparable<TagInfo> {

        private Long taskPayloadId;
        private String tagKey;
        private String tagParent;
        private String tagValue;

        public TagInfo() { }

        public TagInfo(String tagKey, String tagParent, String tagValue) {
            this.tagKey = tagKey;
            this.tagParent = tagParent;
            this.tagValue = tagValue;
        }

        public TagInfo(Long taskPayloadId, String tagKey, String tagParent, String tagValue) {
            this.taskPayloadId = taskPayloadId;
            this.tagKey = tagKey;
            this.tagParent = tagParent;
            this.tagValue = tagValue;
        }

        public Long getTaskPayloadId() {
            return taskPayloadId;
        }

        public void setTaskPayloadId(Long taskPayloadId) {
            this.taskPayloadId = taskPayloadId;
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
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TagInfo tagInfo = (TagInfo) o;

            if (!tagKey.equals(tagInfo.tagKey)) return false;
            if (!tagParent.equals(tagInfo.tagParent)) return false;
            return tagValue.equals(tagInfo.tagValue);
        }

        @Override
        public int hashCode() {
            int result = tagKey.hashCode();
            result = 31 * result + tagParent.hashCode();
            result = 31 * result + tagValue.hashCode();
            return result;
        }

        @Override
        public int compareTo(TagInfo o) {
            if (taskPayloadId==o.taskPayloadId) {
                return 0;
            } else if (taskPayloadId>o.taskPayloadId) {
                return 1;
            } else {
                return -1;
            }
        }

        @Override
        public String toString() { return new Gson().toJson(this); }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
