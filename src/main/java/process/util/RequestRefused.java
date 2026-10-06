package process.util;

/**
 * A request refused for a reason the person can act on -- a malformed date, a status that does not
 * exist. The platform's contract (ADR-023, MIG-103): an HTTP 200 carrying status ERROR and this
 * sentence, never a 500, so a refusal never looks like an outage or pages anyone. It is still the
 * IllegalArgumentException it always was, so every existing catch of one keeps working.
 */
public class RequestRefused extends IllegalArgumentException {

    public RequestRefused(String sentence) {
        super(sentence);
    }
}
