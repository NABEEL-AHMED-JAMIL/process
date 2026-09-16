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
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The inverse of {@link #makeXml}: a task payload read back as the tag rows it was built from.
     *
     * <b>Why this has to exist.</b> A task carries the same configuration twice -- as the XML in
     * {@code source_task.task_payload}, which is what the worker actually runs, and as
     * {@code source_task_payload} rows, which are what the editor shows. They were written
     * independently: a caller supplying only the XML got a task that RAN correctly and EDITED as
     * blank, and one updating the XML without the tags left the tags describing the previous
     * payload. Either way the editor then showed something the task does not do, and saving from
     * that screen wrote it back over the XML that did.
     *
     * So the tags are now derived from the XML wherever they were not supplied, and the XML is the
     * source of truth. Static because the callers that need it are validation and service code
     * that have no reason to hold this component.
     *
     * Returns an empty list rather than throwing for payload that is not parseable XML -- a task
     * can be saved with a hand-written payload that is not yet valid, and refusing to load its
     * editor is a worse answer than opening it with nothing filled in.
     */
    public static List<ConfigurationMakerRequest.TagInfo> parseXmlToTags(String xml) {
        List<ConfigurationMakerRequest.TagInfo> tagInfos = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return tagInfos;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // A task payload is operator-supplied text. Without these an entity declaration in it
            // reads local files or opens network connections from inside the application.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            traverseTags(root, root.getNodeName(), tagInfos);
        } catch (Exception ex) {
            LoggerFactory.getLogger(XmlOutTagInfoUtil.class)
                .warn("Task payload could not be read as XML; its editor will open empty: {}", ex.getMessage());
            return new ArrayList<>();
        }
        return tagInfos;
    }

    /** Depth-first walk; only leaf elements carry a value, matching what makeXml writes. */
    private static void traverseTags(Node node, String parent,
        List<ConfigurationMakerRequest.TagInfo> tagInfos) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        NodeList children = node.getChildNodes();
        boolean hasElementChild = false;
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
        ConfigurationMakerRequest.TagInfo tagInfo = new ConfigurationMakerRequest.TagInfo();
        tagInfo.setTagKey(node.getNodeName());
        tagInfo.setTagParent(parent);
        tagInfo.setTagValue(value);
        tagInfos.add(tagInfo);

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                traverseTags(child, node.getNodeName(), tagInfos);
            }
        }
    }

    @Override
    public String toString() { return new Gson().toJson(this); }

}