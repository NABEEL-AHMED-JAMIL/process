package process.util;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import process.model.dto.ConfigurationMakerRequest;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.annotation.PostConstruct;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class XmlOutTagInfoUtil {

    public Logger logger = LoggerFactory.getLogger(XmlOutTagInfoUtil.class);

    final String BLANK = "";
    final String UTF8 ="UTF-8";
    final String YES = "yes";
    final String NAME = "{http://xml.apache.org/xslt}indent-amount";
    final String VALUE = "2";
    /** The document element every payload in this system carries. */
    final String ROOT_TAG = "pipeline";

    /*
     * This is a singleton @Component and makeXml runs on request threads, so nothing here may be
     * shared mutable state. DocumentBuilder, DocumentBuilderFactory and TransformerFactory are
     * all documented as not thread-safe, and the previous version held one DocumentBuilder in a
     * field for every caller to use at once. Confining each to its own thread costs one cheap
     * construction per thread and removes the question entirely.
     */
    private final ThreadLocal<DocumentBuilder> builders = ThreadLocal.withInitial(() -> {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create an XML document builder", ex);
        }
    });

    private final ThreadLocal<TransformerFactory> transformerFactories =
        ThreadLocal.withInitial(TransformerFactory::newInstance);

    @PostConstruct
    public void init() {
        logger.info("============Xml Factory InIt============");
        // Fail at startup rather than on the first save if the platform has no XML support.
        this.newDocument();
        logger.info("============Xml Factory End============");
    }

    private Document newDocument() {
        return this.builders.get().newDocument();
    }

    public String makeXml(ConfigurationMakerRequest xmlMakerRequest) throws Exception {
        logger.info("Process For Xml Create Start");
        String xml = null;
        if(xmlMakerRequest.getXmlTagsInfo() != null) {
            Document xmlDoc = this.newDocument();
            /*
             * An XML document has exactly one root, and it is this wrapper -- never the first tag
             * in the list.
             *
             * Promoting the first tag to root (what this did before) silently destroyed it: a
             * Pipeline Form's fields are a flat list with no parent, so field #1 became the
             * document element and its value became that element's mixed-content text. The
             * worker's parsers all read root.find("<tag>"), which cannot match the root itself,
             * so the first field of every form-authored task arrived as None and the job failed
             * on "missing required setting". Verified against the live endpoint before the change.
             *
             * <pipeline> is the name every payload in the system already carries, and no parser
             * inspects the root's name, so existing tasks keep parsing exactly as they did.
             */
            Element root = xmlDoc.createElementNS(BLANK, ROOT_TAG);
            xmlDoc.appendChild(root);

            for(ConfigurationMakerRequest.TagInfo tagInfo: xmlMakerRequest.getXmlTagsInfo()) {
                String tagKey = tagInfo.getTagKey();
                String tagParent = tagInfo.getTagParent();
                String tagValue = tagInfo.getTagValue();

                // A row with no key cannot become an element; skipping it keeps one blank row in
                // the editor from failing the whole save.
                if(tagKey == null || tagKey.equals(BLANK)) {
                    continue;
                }

                Element child = xmlDoc.createElement(tagKey);
                addTagValue(xmlDoc, child, tagValue);

                if(tagParent != null && !tagParent.equals(BLANK)) {
                    NodeList nodeList = xmlDoc.getElementsByTagName(tagParent);
                    if(nodeList != null && nodeList.getLength() > 0) {
                        nodeList.item(nodeList.getLength()-1).appendChild(child);
                    } else {
                        // The parent tag has no row of its own -- create the wrapper it names.
                        Element parent = xmlDoc.createElement(tagParent);
                        parent.appendChild(child);
                        root.appendChild(parent);
                    }
                } else {
                    root.appendChild(child);
                }
            }

            Transformer transformer = this.transformerFactories.get().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, UTF8);
            transformer.setOutputProperty(OutputKeys.INDENT, YES);
            transformer.setOutputProperty(NAME, VALUE);
            DOMSource source = new DOMSource(xmlDoc);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            transformer.transform(source,result);
            xml = result.getWriter().toString();
        }
        logger.info("Process For Xml Create End");
        return xml;
    }



    private void addTagValue(Document xmlDoc, Element child, String tagValue) {
        if(!ProcessUtil.isNull(tagValue)) {
            child.appendChild(xmlDoc.createTextNode(tagValue));
        } else {
            child.appendChild(xmlDoc.createTextNode(BLANK));
        }
    }

    @Override
    public String toString() { return new Gson().toJson(this); }

}