package process.util;

import java.util.regex.Pattern;

/**
 * Cleans raw extracted text (PDF/OCR/copy-paste output is often messy) into a tight, plain
 * block suitable for use as an AI prompt's user message, or for any downstream consumer that
 * just wants normalized text. Exposed over HTTP (see TextCleanerRestApi) so it's usable from
 * anywhere -- the Angular UI, a Source Task, a Dynamic Form, or an external caller (e.g. the
 * job-search Python listeners) -- not just from this codebase.
 *
 * Each step targets a specific class of noise commonly seen coming out of PDF text layers and
 * pasted content:
 *  - CRLF/CR line endings -> LF
 *  - non-printable/control characters (keeps \n and \t)
 *  - hyphenated line-wraps from justified PDF text, e.g. "hyper-\nlink" -> "hyperlink"
 *  - "smart" quotes/dashes/ellipsis -> plain ASCII equivalents
 *  - runs of spaces/tabs -> single space
 *  - trailing whitespace per line
 *  - 3+ consecutive blank lines -> a single blank line
 *  - leading/trailing whitespace on the whole block
 *
 * @author Nabeel Ahmed
 */
public final class TextCleanerUtil {

    private TextCleanerUtil() {}

    private static final Pattern CRLF = Pattern.compile("\r\n?");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern HYPHEN_WRAP = Pattern.compile("([a-zA-Z])-\n([a-zA-Z])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \t]{2,}");
    private static final Pattern TRAILING_LINE_SPACE = Pattern.compile("[ \t]+$", Pattern.MULTILINE);
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n{3,}");

    /**
     * Method use to clean raw extracted text into plain, whitespace-normalized text
     * @param raw
     * @return String
     * */
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
