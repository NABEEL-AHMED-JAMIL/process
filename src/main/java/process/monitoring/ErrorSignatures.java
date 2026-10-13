package process.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MIG-355: a failed run's error, grouped as log tools group lines into patterns -- what varies from run to run (numbers,
 * ids, quoted values, paths, addresses, a step's own key) becomes a placeholder, so "The step's input has 1,000,000 rows x
 * 8 columns" and "... 100,000 rows x 40 columns" are one signature. And its kind, in words an operator sorts by: timed
 * out, too large for memory, the data, a service it called, rejected by a reviewer, its settings, closed by a person,
 * or other.
 */
public final class ErrorSignatures {

    public static final String TIMED_OUT = "timed_out";
    public static final String TOO_LARGE = "too_large";
    public static final String DATA = "data";
    public static final String SERVICE = "service";
    public static final String REJECTED = "rejected";
    public static final String SETTINGS = "settings";
    public static final String OPERATOR = "operator";
    public static final String OTHER = "other";

    private static final Pattern STEP_PREFIX = Pattern.compile("^Step \\d+ <[^>]*> failed( after \\d+ tr(y|ies))?: ");
    private static final Pattern FAILED_AFTER = Pattern.compile("^Failed( after \\d+ tr(y|ies))?: ");
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-fA-F]{16,}\\b");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>\"']+@[^\\s@<>\"']+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("\\bhttps?://\\S+");
    private static final Pattern PATH = Pattern.compile("(?<![A-Za-z0-9])(/[A-Za-z0-9._-]+){2,}/?");
    private static final Pattern QUOTED = Pattern.compile("(\"[^\"]{0,200}\"|'[^']{0,200}')");
    private static final Pattern ANGLED = Pattern.compile("<[A-Za-z0-9_.-]{1,64}>");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d[\\d,]*(\\.\\d+)?(?![A-Za-z])");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    static final int MAX_PATTERN = 200;

    private ErrorSignatures() {
    }

    /** The error's pattern: its variable parts as placeholders, at most {@value #MAX_PATTERN} characters. */
    public static String pattern(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "(no message)";
        }
        String text = SPACES.matcher(message.trim()).replaceAll(" ");
        text = STEP_PREFIX.matcher(text).replaceFirst("");
        text = FAILED_AFTER.matcher(text).replaceFirst("");
        // A step's own key (<report>) first, before the placeholders below are written in the same brackets.
        text = ANGLED.matcher(text).replaceAll("<step>");
        text = UUID.matcher(text).replaceAll("<id>");
        text = URL.matcher(text).replaceAll("<url>");
        text = EMAIL.matcher(text).replaceAll("<email>");
        text = PATH.matcher(text).replaceAll("<path>");
        text = QUOTED.matcher(text).replaceAll("<value>");
        text = HEX.matcher(text).replaceAll("<id>");
        text = NUMBER.matcher(text).replaceAll("<n>");
        return text.length() <= MAX_PATTERN ? text : text.substring(0, MAX_PATTERN - 1) + "…";
    }

    /** A short stable id for a signature (the task and the pattern), for links and the console's selection. */
    public static String id(String task, String pattern) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(((task == null ? "" : task) + "\n" + pattern).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The error's kind, from how the run ended (job_queue.end_reason and status), whether the step timed out, and its words. */
    public static String kind(String jobStatus, String endReason, Boolean timedOut, String message) {
        String reason = endReason == null ? "" : endReason;
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if ("OPERATOR".equals(reason)) {
            return OPERATOR;
        }
        if (Boolean.TRUE.equals(timedOut) || "STALLED".equals(reason) || "TOKEN_EXPIRED".equals(reason) || text.contains("timed out")
            || text.contains("timeout") || "Interrupt".equals(jobStatus)) {
            return TIMED_OUT;
        }
        if ("REFUSED".equals(reason) || "AI_STEP".equals(reason) || "DECLINED".equals(reason) || text.contains("not enabled")
            || text.contains("not configured") || text.contains("no such") || text.contains("is required") || text.contains("permission")) {
            return SETTINGS;
        }
        if (text.contains("rejected by")) {
            return REJECTED;
        }
        if (text.contains("in memory") || text.contains("http 413") || text.contains("too large") || text.contains("at most")
            || text.contains("out of memory") || text.contains("exceeds")) {
            return TOO_LARGE;
        }
        if ("DISPATCH".equals(reason) || text.matches(".*http 5\\d\\d.*") || text.contains("could not reach") || text.contains("unavailable")
            || text.contains("connection refused") || text.contains("did not answer") || text.contains("service")) {
            return SERVICE;
        }
        if (text.contains("not a number") || text.contains("has no column") || text.contains("invalid") || text.contains("contract")
            || text.contains("parse") || text.contains("row ") || text.contains("column") || text.contains("(line ")) {
            return DATA;
        }
        return OTHER;
    }

    /** The kind in words, for the console's facet. */
    public static String label(String kind) {
        switch (kind) {
            case TIMED_OUT: return "Timed out or stopped responding";
            case TOO_LARGE: return "Too large for memory or a limit";
            case DATA: return "The data";
            case SERVICE: return "A service it called";
            case REJECTED: return "Rejected by a reviewer";
            case SETTINGS: return "Its settings";
            case OPERATOR: return "Closed by a person";
            default: return "Other";
        }
    }
}
