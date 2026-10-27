package process.customer;

import org.barco.platform.api.Problem;

import java.util.Map;

/**
 * One answer of the customer API (MIG-332): its status, its body -- a resource, or an RFC 9457 problem -- and, for a
 * created resource, its Location. A replay is the first answer again. MIG-426: or a document (a run's result as pdf, html
 * or md), whose bytes are the body.
 */
public final class CustomerAnswer {

    public final int status;
    public final Map<String, Object> body;
    public final String location;
    public final boolean replayed;
    /** MIG-426: the answer is this document rather than JSON; null for every other answer. */
    public final ResultDocument document;

    CustomerAnswer(int status, Map<String, Object> body, String location, boolean replayed) {
        this(status, body, location, replayed, null);
    }

    private CustomerAnswer(int status, Map<String, Object> body, String location, boolean replayed, ResultDocument document) {
        this.status = status;
        this.body = body;
        this.location = location;
        this.replayed = replayed;
        this.document = document;
    }

    static CustomerAnswer of(int status, Map<String, Object> body, String location) {
        return new CustomerAnswer(status, body, location, false);
    }

    /** The problem as the answer, about this request path ("/v1/events"). */
    static CustomerAnswer problem(Problem problem, String instance) {
        return new CustomerAnswer(problem.getStatus(), problem.at(instance).toMap(), null, false);
    }

    /** MIG-426: a document as the answer (200). */
    static CustomerAnswer document(ResultDocument document) {
        return new CustomerAnswer(200, null, null, false, document);
    }

    /** MIG-426: a document's outcome as the answer: the document, or its problem about {@code instance}. */
    static CustomerAnswer of(RunDocuments.Outcome outcome, String instance) {
        return outcome.document != null ? document(outcome.document)
            : new CustomerAnswer(outcome.problem.getStatus(), outcome.problemBody(instance), null, false);
    }

    /** This answer with a run's Location and replay mark (run-and-wait's document, or its problem). */
    CustomerAnswer at(String location, boolean replayed) {
        return new CustomerAnswer(this.status, this.body, location, replayed, this.document);
    }

    public boolean isProblem() {
        return this.status >= 400;
    }
}
