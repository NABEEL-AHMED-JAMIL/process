package process.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ContentTypeUtil {

    private ContentTypeUtil() {}

    private static final Map<String, String> EXTENSION_CONTENT_TYPES = new HashMap<>();
    static {
        EXTENSION_CONTENT_TYPES.put("json", "application/json");
        EXTENSION_CONTENT_TYPES.put("csv", "text/csv");
        EXTENSION_CONTENT_TYPES.put("txt", "text/plain");
        EXTENSION_CONTENT_TYPES.put("xml", "application/xml");
        EXTENSION_CONTENT_TYPES.put("md", "text/markdown");
        EXTENSION_CONTENT_TYPES.put("pdf", "application/pdf");
        EXTENSION_CONTENT_TYPES.put("mp3", "audio/mpeg");
        EXTENSION_CONTENT_TYPES.put("m4a", "audio/mp4");
        EXTENSION_CONTENT_TYPES.put("mp4", "video/mp4");
        EXTENSION_CONTENT_TYPES.put("jpg", "image/jpeg");
        EXTENSION_CONTENT_TYPES.put("jpeg", "image/jpeg");
        EXTENSION_CONTENT_TYPES.put("png", "image/png");
        EXTENSION_CONTENT_TYPES.put("gif", "image/gif");
        EXTENSION_CONTENT_TYPES.put("webp", "image/webp");
        EXTENSION_CONTENT_TYPES.put("svg", "image/svg+xml");
        EXTENSION_CONTENT_TYPES.put("bmp", "image/bmp");
        EXTENSION_CONTENT_TYPES.put("html", "text/html");
        EXTENSION_CONTENT_TYPES.put("htm", "text/html");
        EXTENSION_CONTENT_TYPES.put("doc", "application/msword");
        EXTENSION_CONTENT_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        EXTENSION_CONTENT_TYPES.put("odt", "application/vnd.oasis.opendocument.text");
        EXTENSION_CONTENT_TYPES.put("ott", "application/vnd.oasis.opendocument.text-template");
        EXTENSION_CONTENT_TYPES.put("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template");
        EXTENSION_CONTENT_TYPES.put("rtf", "text/rtf");
        EXTENSION_CONTENT_TYPES.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        EXTENSION_CONTENT_TYPES.put("ots", "application/vnd.oasis.opendocument.spreadsheet-template");
        EXTENSION_CONTENT_TYPES.put("xls", "application/vnd.ms-excel");
        EXTENSION_CONTENT_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        EXTENSION_CONTENT_TYPES.put("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template");
        EXTENSION_CONTENT_TYPES.put("tsv", "text/tab-separated-values");
        EXTENSION_CONTENT_TYPES.put("odp", "application/vnd.oasis.opendocument.presentation");
        EXTENSION_CONTENT_TYPES.put("otp", "application/vnd.oasis.opendocument.presentation-template");
        EXTENSION_CONTENT_TYPES.put("ppt", "application/vnd.ms-powerpoint");
        EXTENSION_CONTENT_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        EXTENSION_CONTENT_TYPES.put("potx", "application/vnd.openxmlformats-officedocument.presentationml.template");
        EXTENSION_CONTENT_TYPES.put("odg", "application/vnd.oasis.opendocument.graphics");
        EXTENSION_CONTENT_TYPES.put("otg", "application/vnd.oasis.opendocument.graphics-template");

        EXTENSION_CONTENT_TYPES.put("fodt", "application/vnd.oasis.opendocument.text-flat-xml");
        EXTENSION_CONTENT_TYPES.put("fods", "application/vnd.oasis.opendocument.spreadsheet-flat-xml");
        EXTENSION_CONTENT_TYPES.put("fodp", "application/vnd.oasis.opendocument.presentation-flat-xml");
        EXTENSION_CONTENT_TYPES.put("fodg", "application/vnd.oasis.opendocument.graphics-flat-xml");
        EXTENSION_CONTENT_TYPES.put("tif", "image/tiff");
        EXTENSION_CONTENT_TYPES.put("tiff", "image/tiff");
        EXTENSION_CONTENT_TYPES.put("vsd", "application/vnd.visio");
        EXTENSION_CONTENT_TYPES.put("vsdx", "application/vnd.ms-visio.drawing");

        EXTENSION_CONTENT_TYPES.put("xhtml", "application/xhtml+xml");
        EXTENSION_CONTENT_TYPES.put("sxw", "application/vnd.sun.xml.writer");
        EXTENSION_CONTENT_TYPES.put("sxc", "application/vnd.sun.xml.calc");
        EXTENSION_CONTENT_TYPES.put("sxi", "application/vnd.sun.xml.impress");
        EXTENSION_CONTENT_TYPES.put("wpd", "application/wordperfect");
        EXTENSION_CONTENT_TYPES.put("swf", "application/x-shockwave-flash");
    }

    private static final Set<String> PREVIEWABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "json", "csv", "txt", "xml", "md", "pdf", "mp3", "m4a", "mp4",
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "doc", "docx"));

    public static String extensionOf(String key) {
        if (key == null) {
            return "";
        }
        int dot = key.lastIndexOf('.');
        return dot >= 0 && dot < key.length() - 1 ? key.substring(dot + 1).toLowerCase() : "";
    }

    public static String contentTypeFor(String key) {
        String contentType = EXTENSION_CONTENT_TYPES.get(extensionOf(key));
        return contentType != null ? contentType : "application/octet-stream";
    }

    /**
     * The extension underneath a .gz wrapper -- "audit.json.gz" -> "json". Log storage is full
     * of gzipped text (CloudTrail writes every file this way), and what matters for preview is
     * what the file becomes once unwrapped, not the wrapper. Returns "" when there is no inner
     * extension to read.
     */
    public static String innerExtensionOfGzip(String key) {
        if (!"gz".equals(extensionOf(key))) {
            return "";
        }
        return extensionOf(key.substring(0, key.length() - ".gz".length()));
    }

    public static boolean isGzip(String key) {
        return "gz".equals(extensionOf(key));
    }

    /** Gzipped text can be previewed; gzipped anything-else can't. */
    public static boolean isPreviewableGzip(String key) {
        return isGzip(key) && GZIP_PREVIEWABLE_INNER.contains(innerExtensionOfGzip(key));
    }

    private static final Set<String> GZIP_PREVIEWABLE_INNER =
        new HashSet<>(Arrays.asList("json", "csv", "txt", "xml", "md", "log", "tsv", "ndjson"));

    public static boolean isPreviewable(String key) {
        return PREVIEWABLE_EXTENSIONS.contains(extensionOf(key)) || isPreviewableGzip(key);
    }

}
