package process.customer;

import org.barco.platform.api.Problem;

import java.util.Map;

/**
 * One answer of the customer API (MIG-332): its status, its body -- a resource, or an RFC 9457 problem -- and, for a
 * created resource, its Location. A replay is the first answer again.
 */
public final class CustomerAnswer {

    public final int status;
    public final Map<String, Object> body;
    public final String location;
    public final boolean replayed;

    CustomerAnswer(int status, Map<String, Object> body, String location, boolean replayed) {
        this.status = status;
        this.body = body;
        this.location = location;
        this.replayed = replayed;
    }

    static CustomerAnswer of(int status, Map<String, Object> body, String location) {
        return new CustomerAnswer(status, body, location, false);
    }

    /** The problem as the answer, about this request path ("/v1/events"). */
    static CustomerAnswer problem(Problem problem, String instance) {
        return new CustomerAnswer(problem.getStatus(), problem.at(instance).toMap(), null, false);
    }

    public boolean isProblem() {
        return this.status >= 400;
    }
}
