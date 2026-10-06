package process.pipeline.review;

import process.model.enums.ReviewParty;
import process.security.TenantContext;

import java.util.Optional;

/**
 * Which review party the caller is (MIG-237), from the caller's token alone -- never from the request -- so neither
 * side can record the other's review, whichever endpoint it comes through:
 *
 * <ul>
 *   <li><b>INTERNAL</b>: the workspace's own people in the console -- a tenant administrator or tenant user of the run's
 *       workspace. Of them, only a tenant administrator decides ({@link #mayDecide}); a tenant user reads.</li>
 *   <li><b>CUSTOMER</b>: the workspace's API clients (MIG-332), whose token carries the role {@link #API_CLIENT_ROLE};
 *       POST /v1/runs/{id}/review (MIG-334) is its way in.</li>
 *   <li>Anyone else -- a platform administrator, a caller with no role -- is neither party and records no review.</li>
 * </ul>
 */
public final class ReviewParties {

    /** The role an API client's token carries (MIG-332's TenantContext.API_CLIENT, the same word). */
    public static final String API_CLIENT_ROLE = "API_CLIENT";

    /** The internal review, asked of anyone who is not the workspace's own. */
    static final String INTERNAL_IS_OURS = "An internal review is recorded by the workspace's tenant administrators in the console; "
        + "a customer cannot record one.";
    /** The customer's review, asked of anyone who is not the customer -- the console included. */
    static final String CUSTOMER_IS_THEIRS = "A customer review is recorded by the customer through the API; the console records the "
        + "internal review only.";

    private ReviewParties() {}

    public static Optional<ReviewParty> ofCaller() {
        String role = TenantContext.getUserRole();
        if ("TENANT_ADMIN".equals(role) || "TENANT_USER".equals(role)) {
            return Optional.of(ReviewParty.INTERNAL);
        }
        return API_CLIENT_ROLE.equals(role) ? Optional.of(ReviewParty.CUSTOMER) : Optional.empty();
    }

    /**
     * Why the caller may not record {@code party}'s review, in words a person reads; empty when they may. The rule the
     * console's endpoint and the customer's (POST /v1/runs/{id}/review) both go through.
     */
    public static Optional<String> refusal(ReviewParty party) {
        Optional<ReviewParty> caller = ofCaller();
        if (!caller.isPresent()) {
            return Optional.of("Only the workspace's own people and its API clients review a run's results.");
        }
        if (caller.get() != party) {
            return Optional.of(party == ReviewParty.INTERNAL ? INTERNAL_IS_OURS : CUSTOMER_IS_THEIRS);
        }
        if (party == ReviewParty.INTERNAL && !mayDecide()) {
            return Optional.of("Only a tenant administrator can approve or reject a run's results.");
        }
        return Optional.empty();
    }

    /** An internal reviewer decides when they are a tenant administrator of the workspace. */
    static boolean mayDecide() {
        return "TENANT_ADMIN".equals(TenantContext.getUserRole());
    }
}
