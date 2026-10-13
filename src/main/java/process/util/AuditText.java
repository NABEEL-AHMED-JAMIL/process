package process.util;

import java.util.regex.Pattern;

/**
 * A run's audit line ends each sentence in one full stop (risk register #83, 2026-10-12). Lines are built by joining a
 * fixed sentence to someone's words -- a review comment or reason, an approval's comment, a worker's message -- and a
 * sentence that already ends in "." got another: "Comment: Checked.." (MIG-346 fixed the approval step's lines only).
 *
 * {@link #oneFullStop} is applied where every audit line is written (TransactionServiceImpl.saveJobAuditLogs, OpenSearch
 * and the database alike), so no writer -- the bulk suffix " Bulk decision &lt;bulkId&gt;." included -- has to remember it.
 * {@link #sentence} ends a piece of text in exactly one mark, for a line built elsewhere (a step's status line).
 */
public final class AuditText {

    /** A ".", "?" or "!" followed by one more "." at the end of a sentence; an ellipsis ("...") is left alone. */
    private static final Pattern DOUBLED = Pattern.compile("(?<!\\.)([.?!])\\.(?=\\s|$)");

    private AuditText() {
    }

    /** The line with every doubled full stop at a sentence's end made one: "Checked.. The run" becomes "Checked. The run". */
    public static String oneFullStop(String line) {
        return line == null || line.indexOf('.') < 0 ? line : DOUBLED.matcher(line).replaceAll("$1");
    }

    /** The text, trimmed, ending in exactly one ".", "?" or "!": "Approved by Ana: checked." rather than "checked..". */
    public static String sentence(String text) {
        String trimmed = text == null ? "" : text.trim();
        char last = trimmed.isEmpty() ? '.' : trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '?' || last == '!' ? trimmed : trimmed + ".";
    }
}
