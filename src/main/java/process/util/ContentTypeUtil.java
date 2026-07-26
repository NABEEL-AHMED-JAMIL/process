package process.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility use to map an object key's file extension to a content type and to decide whether
 * the Bucket Browser can preview it inline. Only json/csv/txt/pdf are previewable for now --
 * html and doc/docx (and anything else) are download-only.
 * @author Nabeel Ahmed
 */
public final class ContentTypeUtil {

    private ContentTypeUtil() {}

    private static final Map<String, String> EXTENSION_CONTENT_TYPES = new HashMap<>();
    static {
        EXTENSION_CONTENT_TYPES.put("json", "application/json");
        EXTENSION_CONTENT_TYPES.put("csv", "text/csv");
        EXTENSION_CONTENT_TYPES.put("txt", "text/plain");
        EXTENSION_CONTENT_TYPES.put("pdf", "application/pdf");
        EXTENSION_CONTENT_TYPES.put("html", "text/html");
        EXTENSION_CONTENT_TYPES.put("htm", "text/html");
        EXTENSION_CONTENT_TYPES.put("doc", "application/msword");
        EXTENSION_CONTENT_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private static final Set<String> PREVIEWABLE_EXTENSIONS = new HashSet<>(Arrays.asList("json", "csv", "txt", "pdf"));

    /**
     * Method use to get the lower-cased file extension of an object key, empty if none
     * @param key
     * @return String
     * */
    public static String extensionOf(String key) {
        if (key == null) {
            return "";
        }
        int dot = key.lastIndexOf('.');
        return dot >= 0 && dot < key.length() - 1 ? key.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Method use to get the content type to serve an object key with
     * @param key
     * @return String
     * */
    public static String contentTypeFor(String key) {
        String contentType = EXTENSION_CONTENT_TYPES.get(extensionOf(key));
        return contentType != null ? contentType : "application/octet-stream";
    }

    /**
     * Method use to check if an object key can be previewed inline (json/csv/txt/pdf only)
     * @param key
     * @return boolean
     * */
    public static boolean isPreviewable(String key) {
        return PREVIEWABLE_EXTENSIONS.contains(extensionOf(key));
    }

}
