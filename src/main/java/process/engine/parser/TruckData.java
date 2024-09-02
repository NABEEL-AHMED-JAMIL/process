package process.engine.parser;

import com.google.gson.Gson;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * @author Nabeel Ahmed
 */
@XmlRootElement
public class TruckData {

    private String outputFolder;
    private String inputFolder;
    private String folderPattern;
    private String validType;
    private Integer fetchLimit;
    private String rootFolder;

    public TruckData() {
    }

    public String getOutputFolder() {
        return outputFolder;
    }

    public void setOutputFolder(String outputFolder) {
        this.outputFolder = outputFolder;
    }

    public String getInputFolder() {
        return inputFolder;
    }

    public void setInputFolder(String inputFolder) {
        this.inputFolder = inputFolder;
    }

    public String getFolderPattern() {
        return folderPattern;
    }

    public void setFolderPattern(String folderPattern) {
        this.folderPattern = folderPattern;
    }

    public String getValidType() {
        return validType;
    }

    public void setValidType(String validType) {
        this.validType = validType;
    }

    public Integer getFetchLimit() {
        return fetchLimit;
    }

    public void setFetchLimit(Integer fetchLimit) {
        this.fetchLimit = fetchLimit;
    }

    public String getRootFolder() {
        return rootFolder;
    }

    public void setRootFolder(String rootFolder) {
        this.rootFolder = rootFolder;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
