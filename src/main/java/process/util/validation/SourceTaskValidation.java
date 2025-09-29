package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.xml.sax.InputSource;
import process.model.dto.ConfigurationMakerRequest;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * This SourceTaskValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskValidation {

    private Integer rowCounter = 0;
    private String sourceTaskTypeId;
    private String taskName;
    private String taskPayload;
    private String homePageId;
    private String pipelineId;
    private List<ConfigurationMakerRequest.TagInfo> xmlTagsInfo;
    private String errorMsg;

    public SourceTaskValidation() {}

    public SourceTaskValidation(String sourceTaskTypeId,
        String taskName,
        String taskPayload,
        String pipelineId,
        String homePageId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.taskName = taskName;
        this.taskPayload = taskPayload;
        this.homePageId = homePageId;
        this.pipelineId = pipelineId;
    }

    public Integer getRowCounter() {
        return rowCounter;
    }

    public void setRowCounter(Integer rowCounter) {
        this.rowCounter = rowCounter;
    }

    public String getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(String sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTaskPayload() {
        return taskPayload;
    }

    public void setTaskPayload(String taskPayload) {
        this.taskPayload = taskPayload;
    }

    public String getHomePageId() {
        return homePageId;
    }

    public void setHomePageId(String homePageId) {
        this.homePageId = homePageId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public List<ConfigurationMakerRequest.TagInfo> getXmlTagsInfo() {
        return xmlTagsInfo;
    }

    public void setXmlTagsInfo(List<ConfigurationMakerRequest.TagInfo> xmlTagsInfo) {
        this.xmlTagsInfo = xmlTagsInfo;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        if (isNull(this.errorMsg)) {
            this.errorMsg = errorMsg;
        } else {
            this.errorMsg += errorMsg;
        }
    }

    /**
     * This isValidJobDetail use to validate the
     * job detail of the job valid return true
     * if non-valid return false
     * @return boolean true|false
     * */
    public void isValidSourceTask() {
        if (isNull(this.sourceTaskTypeId)) {
            this.setErrorMsg(String.format("Task type id should not be empty at row %s.<br>", rowCounter));
        }
        if (isNull(this.taskName)) {
            this.setErrorMsg(String.format("Task name should not be empty at row %s.<br>", rowCounter));
        }
        if (isNull(this.taskPayload)) {
            this.setErrorMsg(String.format("Task payload should not be empty at row %s.<br>", rowCounter));
        }
        try {
            if (!isNull(this.taskPayload)) {
                this.setXmlTagsInfo(this.parseXmlToRequest(this.taskPayload));
            }
        } catch (Exception ex) {
            this.setErrorMsg(String.format("Task payload not valid at row %s.<br>", rowCounter));
        }
    }

    /**
     * Method use to parse xml to request
     * @param xml
     * @return List<ConfigurationMakerRequest.TagInfo>
     * */
    public List<ConfigurationMakerRequest.TagInfo> parseXmlToRequest(String xml) throws Exception {
        List<ConfigurationMakerRequest.TagInfo> tagInfos = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        Element root = doc.getDocumentElement();
        traverseXml(root, root.getNodeName(), tagInfos);
        return tagInfos;
    }

    /**
     * Method use to traverse the xml
     * @param node
     * @param parent
     * @param tagInfos
     * */
    private void traverseXml(Node node, String parent, List<ConfigurationMakerRequest.TagInfo> tagInfos) {
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        NodeList children = node.getChildNodes();
        boolean hasElementChild = false;
        // check if node has element children
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElementChild = true;
                break;
            }
        }
        String value = null;
        if (!hasElementChild) {
            value = node.getTextContent() != null ? node.getTextContent().trim() : null;
        }
        // Create TagInfo (tagKey is required, other fields can be null)
        ConfigurationMakerRequest.TagInfo tagInfo = new ConfigurationMakerRequest.TagInfo();
        tagInfo.setTagKey(node.getNodeName());
        tagInfo.setTagParent(parent);
        tagInfo.setTagValue(value);
        tagInfos.add(tagInfo);
        // traverse children
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                traverseXml(child, node.getNodeName(), tagInfos);
            }
        }
    }

    private static boolean isNull(String filed) {
        return filed == null || filed.isEmpty();
    }

        @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
