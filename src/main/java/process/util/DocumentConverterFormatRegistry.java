package process.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
public final class DocumentConverterFormatRegistry {

    private DocumentConverterFormatRegistry() {
    }

    public static final class FormatFamily {
        private final String key;
        private final String label;
        private final List<String> inputFormats;
        private final List<String> outputFormats;

        private FormatFamily(String key, String label, List<String> inputFormats, List<String> outputFormats) {
            this.key = key;
            this.label = label;
            this.inputFormats = inputFormats;
            this.outputFormats = outputFormats;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public List<String> getInputFormats() {
            return inputFormats;
        }

        public List<String> getOutputFormats() {
            return outputFormats;
        }
    }

    private static final Map<String, FormatFamily> FAMILIES = new LinkedHashMap<>();
    static {
        // "md" is served by MarkdownDocumentFormat rather than JODConverter's own registry,
        // which has no Markdown entry (jodconverter/jodconverter#444). LibreOffice registers
        // the filter against the Writer family only, so it belongs to TEXT and nowhere else.
        register("TEXT", "Text Document",
            Arrays.asList("doc", "docx", "dotx", "md", "odt", "ott", "fodt", "rtf", "sxw", "txt", "wpd", "xhtml"),
            Arrays.asList("doc", "docx", "dotx", "md", "odt", "ott", "fodt", "html", "xhtml", "rtf", "sxw", "txt",
                "jpg", "pdf", "png", "svg"));
        register("SPREADSHEET", "Spreadsheet",
            Arrays.asList("csv", "fods", "ods", "ots", "sxc", "tsv", "xls", "xlsx", "xltx"),
            Arrays.asList("csv", "fods", "html", "jpg", "ods", "ots", "pdf", "png", "svg", "sxc", "tsv",
                "xhtml", "xls", "xlsx", "xltx"));
        register("PRESENTATION", "Presentation",
            Arrays.asList("fodp", "odp", "otp", "ppt", "pptx", "potx", "sxi"),
            Arrays.asList("bmp", "fodp", "gif", "html", "jpg", "odp", "otp", "pdf", "png", "ppt", "pptx",
                "potx", "svg", "swf", "sxi", "tif", "xhtml"));
        // "jpeg"/"tiff" are the same formats as "jpg"/"tif" under a longer spelling of the same
        // extension -- JODConverter's own default format registry already maps both spellings to
        // the same JPEG/TIFF DocumentFormat, so listing them here just stops this project's OWN
        // familyOfInput() check from rejecting them before ever reaching JODConverter. ContentTypeUtil
        // already treats jpeg/tiff (and webp) as images (EXTENSION_CONTENT_TYPES); webp is
        // deliberately left off this list -- WebP import support varies by LibreOffice version, and
        // that needs verifying against the actual deployed LibreOffice before being wired in here.
        register("DRAWING", "Drawing / Image",
            Arrays.asList("bmp", "fodg", "gif", "jpg", "jpeg", "odg", "otg", "pdf", "png", "svg", "tif", "tiff", "vsd", "vsdx"),
            Arrays.asList("bmp", "fodg", "gif", "jpg", "jpeg", "odg", "otg", "pdf", "png", "svg", "swf", "tif", "tiff", "vsd", "vsdx"));
        register("OTHER", "Other (HTML)",
            Arrays.asList("html", "htm"),
            Arrays.asList("doc", "docx", "dotx", "odt", "ott", "fodt", "html", "xhtml", "rtf", "sxw", "txt",
                "jpg", "pdf", "png", "svg"));
    }

    private static void register(String key, String label, List<String> inputFormats, List<String> outputFormats) {
        FAMILIES.put(key, new FormatFamily(key, label, inputFormats, outputFormats));
    }

    public static Map<String, FormatFamily> allFamilies() {
        return FAMILIES;
    }

    public static FormatFamily familyOfInput(String inputExtension) {
        String ext = normalize(inputExtension);
        for (FormatFamily family : FAMILIES.values()) {
            if (family.getInputFormats().contains(ext)) {
                return family;
            }
        }
        return null;
    }

    public static boolean isSupportedConversion(String inputExtension, String outputExtension) {
        FormatFamily family = familyOfInput(inputExtension);
        return family != null && family.getOutputFormats().contains(normalize(outputExtension));
    }

    private static String normalize(String extension) {
        if (extension == null) {
            return "";
        }
        String ext = extension.trim().toLowerCase();
        return ext.startsWith(".") ? ext.substring(1) : ext;
    }

}
