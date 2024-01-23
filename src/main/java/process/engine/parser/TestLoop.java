package process.engine.parser;

import com.google.gson.Gson;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * @author Nabeel Ahmed
 */
@XmlRootElement
public class TestLoop {

    private Integer start;
    private Integer end;

    public TestLoop() {
    }

    public Integer getStart() {
        return start;
    }

    public void setStart(Integer start) {
        this.start = start;
    }

    public Integer getEnd() {
        return end;
    }

    public void setEnd(Integer end) {
        this.end = end;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
