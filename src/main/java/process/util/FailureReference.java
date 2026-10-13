package process.util;

import org.barco.platform.correlation.CorrelationId;

/**
 * What a caller is told when something failed on our side (vulnerabilities review 2026-10-13, C8): a fixed sentence and
 * the request's correlation id, never the exception's own text, which can name internal hosts, ports and temp paths.
 * The details stay in the log line written with the failure, which carries the same id (logback's correlationId).
 */
public final class FailureReference {

    private FailureReference() {
    }

    /** {@code sentence} and the current request's correlation id: "The export failed. Reference: 01J...". */
    public static String sentence(String sentence) {
        return sentence(sentence, CorrelationId.current());
    }

    public static String sentence(String sentence, String correlationId) {
        return correlationId == null || correlationId.trim().isEmpty() ? sentence : sentence + " Reference: " + correlationId + ".";
    }
}
