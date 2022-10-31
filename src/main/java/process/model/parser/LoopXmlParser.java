package process.model.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoopXmlParser {

    private Long startIndex;
    private Long endIndex;

    public LoopXmlParser() {}

    public LoopXmlParser(Long startIndex, Long endIndex) {
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    @XmlElement
    public Long getStartIndex() {
        return startIndex;
    }

    public void setStartIndex(Long startIndex) {
        this.startIndex = startIndex;
    }

    @XmlElement
    public Long getEndIndex() {
        return endIndex;
    }

    public void setEndIndex(Long endIndex) {
        this.endIndex = endIndex;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
