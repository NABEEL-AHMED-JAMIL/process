package process.settings;

import org.xml.sax.helpers.DefaultHandler;
import java.util.stream.Collectors;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a task payload names workspace configuration (MIG-167): {@code ${config:KEY}} for a value and
 * {@code ${secret:KEY}} for a secret, KEY in UPPER_SNAKE. The payload keeps the reference -- in the database, in the
 * Kafka message, in the worker's hands -- and the worker asks Core for the value at run time (RunConfigResolver).
 *
 * Also the save-time rule for credentials: a tag (or attribute) whose name says it holds one -- a password, a
 * secret, a token, an API, access or private key, a credential -- may hold nothing, or exactly one
 * {@code ${secret:KEY}}, and never the credential itself. Only the NAME is ever put in a refusal; the value is not.
 */
public final class ConfigReferences {

    /** A key: UPPER_SNAKE, a letter first, at most 64 characters (pipeline_config.config_key's check). */
    public static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{(config|secret):([A-Z][A-Z0-9_]{0,63})}");
    private static final Pattern ANY_REFERENCE = Pattern.compile("\\$\\{(config|secret):([^}]*)}");
    private static final Pattern ONE_SECRET = Pattern.compile("\\$\\{secret:[A-Z][A-Z0-9_]{0,63}}");

    /** The last word of a tag name that makes it a credential, e.g. db_PASSWORD, auth_TOKEN, CREDENTIALS. */
    private static final Set<String> CREDENTIAL_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "password", "passwd", "pwd", "passphrase", "secret", "token", "credential", "credentials",
        "apikey", "accesskey", "privatekey", "secretkey")));

    /** ...or its last two words: api_KEY, secret_access_KEY, private_KEY, secret_KEY. */
    private static final Set<String> CREDENTIAL_PAIRS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "apikey", "accesskey", "privatekey", "secretkey")));

    /** A leaf element when the payload is not well-formed XML: an opening tag, text with no tag in it, its close. */
    private static final Pattern LEAF = Pattern.compile(
        "<([A-Za-z_][\\w.:-]*)(\\s[^<>]*)?>((?:(?!</?[A-Za-z_]).)*?)</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern OPEN_TAG = Pattern.compile("<([A-Za-z_][\\w.:-]*)(\\s[^<>]*?)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][\\w.:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);

    private ConfigReferences() {
    }

    public enum Kind {
        /** ${config:KEY}: a VALUE entry. */
        CONFIG("config", "VALUE"),
        /** ${secret:KEY}: a SECRET entry. */
        SECRET("secret", "SECRET");

        public final String prefix;
        /** pipeline_config.kind */
        public final String entryKind;

        Kind(String prefix, String entryKind) {
            this.prefix = prefix;
            this.entryKind = entryKind;
        }

        static Kind of(String prefix) {
            return "secret".equals(prefix) ? SECRET : CONFIG;
        }
    }

    /** One reference: its kind and key. */
    public static final class Reference {

        public final Kind kind;
        public final String key;

        private Reference(Kind kind, String key) {
            this.kind = kind;
            this.key = key;
        }

        public static Reference config(String key) {
            return new Reference(Kind.CONFIG, key);
        }

        public static Reference secret(String key) {
            return new Reference(Kind.SECRET, key);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Reference && ((Reference) other).kind == this.kind && ((Reference) other).key.equals(this.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.kind, this.key);
        }

        @Override
        public String toString() {
            return "${" + this.kind.prefix + ":" + this.key + "}";
        }
    }

    /** Every well-formed reference in the payload, in order of first appearance. */
    public static Set<Reference> in(String payload) {
        Set<Reference> found = new LinkedHashSet<>();
        if (payload == null) {
            return found;
        }
        Matcher matcher = REFERENCE.matcher(payload);
        while (matcher.find()) {
            found.add(new Reference(Kind.of(matcher.group(1)), matcher.group(2)));
        }
        return found;
    }

    /** Why a ${config:...} or ${secret:...} in the payload names no valid key, or empty when every one does. */
    public static Optional<String> malformed(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        Matcher matcher = ANY_REFERENCE.matcher(payload);
        while (matcher.find()) {
            if (!KEY.matcher(matcher.group(2)).matches()) {
                return Optional.of(String.format("%s does not name a configuration key: a key is UPPER_SNAKE (A-Z, 0-9 and _, "
                    + "starting with a letter, at most 64 characters).", matcher.group()));
            }
        }
        return Optional.empty();
    }

    /** Whether a tag or attribute name says it holds a credential. Judged by its last word, or its last two. */
    public static boolean looksLikeCredential(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String local = name.contains(":") ? name.substring(name.lastIndexOf(':') + 1) : name;
        String spaced = local.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        List<String> words = Arrays.asList(spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"));
        words = words.stream().filter(word -> !word.isEmpty()).collect(Collectors.toList());
        if (words.isEmpty()) {
            return false;
        }
        String last = words.get(words.size() - 1);
        if (CREDENTIAL_WORDS.contains(last)) {
            return true;
        }
        return words.size() > 1 && CREDENTIAL_PAIRS.contains(words.get(words.size() - 2) + last);
    }

    /**
     * Why this payload may not be saved -- a credential-named tag or attribute holding something other than exactly
     * one ${secret:KEY} -- or empty. The sentence names the tag, never what it held.
     */
    public static Optional<String> credentialLiteral(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<Document> document = parse(payload);
        return document.isPresent() ? fromDocument(document.get().getDocumentElement()) : fromText(payload);
    }

    private static Optional<String> fromDocument(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (looksLikeCredential(attribute.getNodeName()) && !isSecretReferenceOrBlank(attribute.getNodeValue())) {
                return Optional.of(attributeSentence(attribute.getNodeName(), element.getTagName()));
            }
        }
        boolean hasChildElements = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasChildElements = true;
                Optional<String> refused = fromDocument((Element) children.item(i));
                if (refused.isPresent()) {
                    return refused;
                }
            }
        }
        if (!hasChildElements && looksLikeCredential(element.getTagName()) && !isSecretReferenceOrBlank(element.getTextContent())) {
            return Optional.of(tagSentence(element.getTagName()));
        }
        return Optional.empty();
    }

    /** The same rule, tag by tag, for a payload that does not parse. */
    private static Optional<String> fromText(String payload) {
        Matcher tag = OPEN_TAG.matcher(payload);
        while (tag.find()) {
            if (tag.group(2) == null) {
                continue;
            }
            Matcher attribute = ATTRIBUTE.matcher(tag.group(2));
            while (attribute.find()) {
                String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
                if (looksLikeCredential(attribute.group(1)) && !isSecretReferenceOrBlank(value)) {
                    return Optional.of(attributeSentence(attribute.group(1), tag.group(1)));
                }
            }
        }
        Matcher leaf = LEAF.matcher(payload);
        while (leaf.find()) {
            String text = CDATA.matcher(leaf.group(3)).replaceAll("$1");
            if (looksLikeCredential(leaf.group(1)) && !isSecretReferenceOrBlank(text)) {
                return Optional.of(tagSentence(leaf.group(1)));
            }
        }
        return Optional.empty();
    }

    private static boolean isSecretReferenceOrBlank(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() || ONE_SECRET.matcher(trimmed).matches();
    }

    private static String tagSentence(String tag) {
        return String.format("The tag <%s> holds a credential as plain text. Keep it in Configuration values as a secret "
            + "and write ${secret:KEY} in the tag instead.", tag);
    }

    private static String attributeSentence(String attribute, String tag) {
        return String.format("The attribute %s on <%s> holds a credential as plain text. Keep it in Configuration values "
            + "as a secret and write ${secret:KEY} there instead.", attribute, tag);
    }

    /** XXE-safe: no DOCTYPE at all, so no entity is ever expanded; such a payload is read as text instead. */
    private static Optional<Document> parse(String payload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return Optional.of(builder.parse(new InputSource(new StringReader(payload))));
        } catch (Exception notXml) {
            return Optional.empty();
        }
    }
}
