package process.inbox;

import java.util.regex.Pattern;

/**
 * A job's inbox trigger's file pattern (MIG-239): a glob on the arriving file's NAME -- '*' any run of characters, '?'
 * one, everything else literal, case ignored -- or nothing, for every file. Never a path: the inbox's keys are
 * Storage's own (intake/&lt;date&gt;/&lt;arrival&gt;-&lt;name&gt;), so a folder in a pattern could never match.
 */
public final class InboxTriggers {

    static final int MAX_PATTERN = 255;

    private InboxTriggers() {
    }

    /** Whether a trigger with this pattern takes a file of this name. */
    public static boolean matches(String pattern, String fileName) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.trim().toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
            .matcher(fileName).matches();
    }

    /** The pattern as stored: trimmed, null for every file; IllegalArgumentException for a path or an overlong one. */
    public static String validPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return null;
        }
        String trimmed = pattern.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("A file pattern matches the file's name, not a folder: use e.g. *.csv or invoices_*.pdf.");
        }
        if (trimmed.length() > MAX_PATTERN) {
            throw new IllegalArgumentException(String.format("A file pattern is at most %d characters.", MAX_PATTERN));
        }
        return trimmed;
    }
}
