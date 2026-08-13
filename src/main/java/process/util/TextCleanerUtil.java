package process.util;

import java.util.regex.Pattern;

public final class TextCleanerUtil {

    private TextCleanerUtil() {}

    private static final Pattern CRLF = Pattern.compile("\r\n?");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern HYPHEN_WRAP = Pattern.compile("([a-zA-Z])-\n([a-zA-Z])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \t]{2,}");
    private static final Pattern TRAILING_LINE_SPACE = Pattern.compile("[ \t]+$", Pattern.MULTILINE);
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n{3,}");

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw;
        text = CRLF.matcher(text).replaceAll("\n");
        text = CONTROL_CHARS.matcher(text).replaceAll("");
        text = HYPHEN_WRAP.matcher(text).replaceAll("$1$2");
        text = text
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('–', '-').replace('—', '-')
            .replace("…", "...");
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        text = TRAILING_LINE_SPACE.matcher(text).replaceAll("");
        text = EXTRA_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

}
