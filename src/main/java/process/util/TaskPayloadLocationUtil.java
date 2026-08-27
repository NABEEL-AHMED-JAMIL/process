package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class TaskPayloadLocationUtil {

    private final Logger logger = LoggerFactory.getLogger(TaskPayloadLocationUtil.class);

    private static final String[] BUCKET_TAGS = { "bucket", "bucket_name" };
    private static final String[] INPUT_FOLDER_TAGS = { "input_folder" };
    private static final String[] OUTPUT_FOLDER_TAGS = { "output_folder"};

    public static class Location {
        private final String bucket;
        private final String inputFolder;
        private final String outputFolder;

        public Location(String bucket, String inputFolder, String outputFolder) {
            this.bucket = bucket;
            this.inputFolder = inputFolder;
            this.outputFolder = outputFolder;
        }

        public String getBucket() {
            return bucket;
        }

        public String getInputFolder() {
            return inputFolder;
        }

        public String getOutputFolder() {
            return outputFolder;
        }
    }

    public Location extract(String taskPayloadXml) {
        if (taskPayloadXml == null || taskPayloadXml.trim().isEmpty()) {
            return new Location(null, null, null);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(taskPayloadXml.getBytes(StandardCharsets.UTF_8)));
            return new Location(
                this.firstTagText(doc, BUCKET_TAGS),
                this.firstTagText(doc, INPUT_FOLDER_TAGS),
                this.firstTagText(doc, OUTPUT_FOLDER_TAGS));
        } catch (Exception ex) {
            this.logger.debug("Could not parse task_payload as XML for bucket/folder extraction: {}", ex.getMessage());
            return new Location(null, null, null);
        }
    }

    private String firstTagText(Document doc, String[] tagNames) {
        for (String tagName : tagNames) {
            NodeList nodes = doc.getElementsByTagName(tagName);
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

}
