package process.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        register("TEXT", "Text Document",
            Arrays.asList("doc", "docx", "odt", "ott", "rtf", "txt"),
            Arrays.asList("doc", "docx", "html", "jpg", "odt", "ott", "fodt", "pdf", "png", "rtf", "txt"));
        register("SPREADSHEET", "Spreadsheet",
            Arrays.asList("csv", "ods", "ots", "tsv", "xls", "xlsx"),
            Arrays.asList("csv", "html", "jpg", "ods", "ots", "fods", "pdf", "png", "tsv", "xls", "xlsx"));
        register("PRESENTATION", "Presentation",
            Arrays.asList("odp", "otp", "ppt", "pptx"),
            Arrays.asList("gif", "html", "jpg", "odp", "otp", "fodp", "pdf", "png", "ppt", "pptx", "bmp"));
        register("DRAWING", "Drawing",
            Arrays.asList("odg", "otg"),
            Arrays.asList("gif", "jpg", "odg", "otg", "fodg", "pdf", "png", "svg", "tif", "vsd", "bmp"));
        register("OTHER", "Other (HTML)",
            Arrays.asList("html"),
            Arrays.asList("doc", "docx", "html", "jpg", "odt", "ott", "fodt", "pdf", "png", "rtf", "txt"));
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
