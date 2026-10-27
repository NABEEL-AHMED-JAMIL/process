package process.customer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * MIG-426 (owner 2026-10-26): the format a client app gets a run's result in -- json (the default, the RunResult as
 * before), pdf, html or md -- one choice wherever a result is handed over (GET /v1/runs/{id}/result, run-and-wait, a
 * webhook's and a Call API step's formats). {@code format=} names it, or the request's Accept; anything else is a 400 that
 * names the allowed ones. No DOCX or XLSX (owner 2026-10-20).
 */
public enum ResultFormat {

    JSON("json", "application/json"),
    PDF("pdf", "application/pdf"),
    HTML("html", "text/html; charset=utf-8"),
    MD("md", "text/markdown; charset=utf-8");

    /** The words format= takes, in the order the answer names them. */
    public static final String ALLOWED = "json, pdf, html, md";
    static final String FORMAT_REFUSAL = "format is one of " + ALLOWED + ".";
    static final String ACCEPT_REFUSAL = "Accept names no type the result is given in: application/json, application/pdf, text/html or "
        + "text/markdown (or use format=" + ALLOWED.replace(", ", "|") + ").";

    public final String word;
    public final String contentType;

    ResultFormat(String word, String contentType) {
        this.word = word;
        this.contentType = contentType;
    }

    /** Whether it is a document made from the run's report (or, without one, from its result rows): pdf, html or md. */
    public boolean document() {
        return this != JSON;
    }

    /** The format a word names (json, pdf, html, md; any case); empty for any other. */
    public static Optional<ResultFormat> of(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        for (ResultFormat f : values()) {
            if (f.word.equals(w)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * The format asked for: {@code format} when given (and then it must be one of {@value #ALLOWED}), else the Accept header's
     * most wanted type the result is given in (q order, then the order written; a wildcard is json), else json. A format word
     * or an Accept that names nothing given is an IllegalArgumentException whose message names the allowed ones.
     */
    public static ResultFormat choose(String format, String accept) {
        if (format != null && !format.trim().isEmpty()) {
            return of(format).orElseThrow(() -> new IllegalArgumentException(FORMAT_REFUSAL));
        }
        if (accept == null || accept.trim().isEmpty()) {
            return JSON;
        }
        List<double[]> ranked = new ArrayList<>();
        List<ResultFormat> found = new ArrayList<>();
        int order = 0;
        for (String part : accept.split(",")) {
            String[] pieces = part.trim().split(";");
            String type = pieces[0].trim().toLowerCase(Locale.ROOT);
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim().toLowerCase(Locale.ROOT);
                if (p.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(p.substring(2).trim());
                    } catch (NumberFormatException unreadable) {
                        q = 0;
                    }
                }
            }
            ResultFormat f = ofType(type);
            if (f != null && q > 0) {
                found.add(f);
                ranked.add(new double[] {q, order});
            }
            order++;
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(ACCEPT_REFUSAL);
        }
        int best = 0;
        for (int i = 1; i < found.size(); i++) {
            if (ranked.get(i)[0] > ranked.get(best)[0]) {
                best = i;
            }
        }
        return found.get(best);
    }

    private static ResultFormat ofType(String type) {
        switch (type) {
            case "application/json":
            case "*/*":
            case "application/*":
                return JSON;
            case "application/pdf":
                return PDF;
            case "text/html":
                return HTML;
            case "text/markdown":
            case "text/x-markdown":
                return MD;
            default:
                return null;
        }
    }
}
