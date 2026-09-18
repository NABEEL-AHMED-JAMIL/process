package process.ai;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import org.xml.sax.InputSource;

/**
 * Reads and writes one tag of a task's payload document (`<pipeline>…</pipeline>`), with the
 * XML parser doing the escaping -- an AI answer can hold anything, and building the tag by
 * string concatenation is how a stray `<` breaks the worker's parser. External entities and
 * DTDs are off: the payload is data the console wrote, but the parser is configured as if it
 * were not.
 */
public final class PayloadXml {

    private final Document doc;

    private PayloadXml(Document doc) { this.doc = doc; }

    public static PayloadXml parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        String source = xml == null || xml.trim().isEmpty() ? "<pipeline/>" : xml;
        return new PayloadXml(f.newDocumentBuilder().parse(new InputSource(new StringReader(source))));
    }

    /** The text of the first element with this tag, or null when the document has none. */
    public String get(String tag) {
        NodeList found = this.doc.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    /** Sets the tag's text, adding the element under the root when it is not there yet. */
    public void set(String tag, String value) {
        NodeList found = this.doc.getElementsByTagName(tag);
        Element element;
        if (found.getLength() > 0) {
            element = (Element) found.item(0);
            while (element.getFirstChild() != null) element.removeChild(element.getFirstChild());
        } else {
            element = this.doc.createElement(tag);
            Node root = this.doc.getDocumentElement();
            root.appendChild(this.doc.createTextNode("\n  "));
            root.appendChild(element);
            root.appendChild(this.doc.createTextNode("\n"));
        }
        element.appendChild(this.doc.createTextNode(value == null ? "" : value));
    }

    /**
     * Appends `<ai_step prompt= version= output= on_error=><var name= from= as=/>…</ai_step>` for
     * a step the worker runs. The worker resolves the variables (a file's contents, when asked),
     * calls aiPrompt.json/run, writes the answer to `output`, and drops the element.
     */
    public void addStep(String promptUuid, int version, String outputTag, String onError, java.util.Map<String, String> variableMap) {
        Element step = this.doc.createElement("ai_step");
        step.setAttribute("prompt", promptUuid);
        step.setAttribute("version", String.valueOf(version));
        step.setAttribute("output", outputTag);
        step.setAttribute("on_error", onError == null ? "fail" : onError);
        for (java.util.Map.Entry<String, String> e : variableMap.entrySet()) {
            Element var = this.doc.createElement("var");
            var.setAttribute("name", e.getKey());
            String source = e.getValue() == null ? "" : e.getValue();
            boolean file = source.startsWith("file:");
            var.setAttribute("from", file ? source.substring(5) : source);
            var.setAttribute("as", file ? "file" : "text");
            step.appendChild(var);
        }
        Node root = this.doc.getDocumentElement();
        root.appendChild(this.doc.createTextNode("\n  "));
        root.appendChild(step);
        root.appendChild(this.doc.createTextNode("\n"));
    }

    @Override
    public String toString() {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(this.doc), new StreamResult(out));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not write the payload document.", ex);
        }
    }
}
