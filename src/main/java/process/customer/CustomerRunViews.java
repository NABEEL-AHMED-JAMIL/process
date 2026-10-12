package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.identity.IdentityPort;
import process.security.TenantContext;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The embeddable run view (MIG-335, ADR-025 decision 10): a signed, short-lived link to a read-only page of one run that a
 * portal shows -- on its own or in an iframe -- without building the screen.
 *
 * <ul>
 *   <li>POST /v1/runs/{runId}/view-links (scope runs:read): {url, expiresAt}, a link to the console's page
 *   /embed/runs/{token}, valid 15 minutes, or less when the portal asks ({"expiresInSeconds": 60..900}). A run that is not
 *   the workspace's is 404. Nothing is stored: the token is the link (ViewLinks), and making one costs nothing but the call.</li>
 *   <li>GET /v1/embed/runs/{token} (the token is the permission): what the page shows -- the run's status and times, its
 *   steps, the files it made with a download link each (FileLinks, ending no later than the view), and its review,
 *   read-only. Read in the link's workspace only (RowSecurity.forTenant: the request has no caller).</li>
 *   <li>GET /v1/embed/runs/{token}/frame: the origins that may frame the page -- the console's server asks before it serves
 *   it and answers Content-Security-Policy frame-ancestors with them ('none' when none, or for a token that is not ours).</li>
 * </ul>
 *
 * Every read asks Identity whether the client that made the link may still act and which origins may frame it: a revoked
 * client's links end at once, and a change of the allow-list holds from the next load. An expired link is 410, a link
 * that is not one of ours -- changed, a file link, another run's with this run's id swapped in -- is 404. The token opens
 * nothing else: not another run, not a file it does not list, not the API (it is no access token).
 */
@Service
public class CustomerRunViews {

    private static final Logger logger = LoggerFactory.getLogger(CustomerRunViews.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String NO_SUCH_VIEW = "No such view. The link may have been changed; ask the portal for a new one.";
    static final String EXPIRED_VIEW = "This view link has expired. Ask the portal for a new one.";
    static final String ENDED_VIEW = "This view link no longer works: the API client that made it was revoked or has expired.";
    /** A run in one of these states changes no more: the page stops refreshing. */
    static final Set<String> SETTLED = new HashSet<>(Arrays.asList("completed", "failed", "skipped", "interrupted"));
    /** How often the page reads a run that is still moving. */
    static final int REFRESH_SECONDS = 10;
    /** The frame-ancestors source list for a page nobody may frame. */
    public static final String NOBODY = "'none'";

    private final CustomerRuns runs;
    private final ViewLinks views;
    private final FileLinks files;
    private final IdentityPort identity;
    private final TransactionOperations transactions;
    private final String consoleUrl;

    @Autowired
    public CustomerRunViews(CustomerRuns runs, ViewLinks views, FileLinks files, IdentityPort identity,
        PlatformTransactionManager transactions, @Value("${customer.embed.console-url:http://localhost:4400}") String consoleUrl) {
        this(runs, views, files, identity, new TransactionTemplate(transactions), consoleUrl);
    }

    /** With the transactions given (a test's, or none). */
    public CustomerRunViews(CustomerRuns runs, ViewLinks views, FileLinks files, IdentityPort identity, TransactionOperations transactions,
        String consoleUrl) {
        this.runs = runs;
        this.views = views;
        this.files = files;
        this.identity = identity;
        this.transactions = transactions;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.trim().replaceAll("/+$", "");
    }

    // ---- POST /v1/runs/{runId}/view-links ----------------------------------------------------------------------------

    public CustomerAnswer create(String runId, byte[] body) {
        String instance = "/v1/runs/" + runId + "/view-links";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > CustomerPipelines.MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "A view link's request is at most 1 MB of JSON."), instance);
        }
        JsonNode request;
        try {
            request = sent.length == 0 ? JSON.createObjectNode() : JSON.readTree(sent);
        } catch (IOException unreadable) {
            return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), instance);
        }
        if (request == null || request.isMissingNode()) {
            request = JSON.createObjectNode();
        }
        if (!request.isObject()) {
            return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {expiresInSeconds}, or nothing."), instance);
        }
        Duration life = ViewLinks.VALID;
        JsonNode asked = request.get("expiresInSeconds");
        if (asked != null && !asked.isNull()) {
            long shortest = ViewLinks.SHORTEST.getSeconds();
            long longest = ViewLinks.VALID.getSeconds();
            if (!asked.isIntegralNumber() || asked.asLong() < shortest || asked.asLong() > longest) {
                return CustomerAnswer.problem(Problem.validation("The link's life is not one this API makes.", Collections.singletonList(
                    new Problem.FieldError("expiresInSeconds", "must be a whole number of seconds from " + shortest + " to " + longest))),
                    instance);
            }
            life = Duration.ofSeconds(asked.asLong());
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        if (!this.views.available()) {
            logger.warn("A view link was asked for but cannot be signed: internal.service-token is not set.");
            return CustomerAnswer.problem(Problem.of(503, "View links cannot be made right now. Try again in a moment."), instance);
        }
        long tenantId = TenantContext.getTenantId();
        String clientId = TenantContext.getClientId();
        ViewLinks.Issued issued = this.views.issue(tenantId, found.get().row.runId, clientId, life);
        logger.info("View link for run {} of workspace {} made by API client {}, until {}", found.get().row.runId, tenantId, clientId,
            ApiTimes.utc(issued.expiresAt));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("url", this.consoleUrl + "/embed/runs/" + issued.token);
        answer.put("expiresAt", ApiTimes.utc(issued.expiresAt));
        return CustomerAnswer.of(201, answer, null);
    }

    // ---- GET /v1/embed/runs/{token} ----------------------------------------------------------------------------------

    /** What the page shows; the token is the only permission. */
    public CustomerAnswer view(String token) {
        String instance = "/v1/embed/runs/{token}";
        ViewLinks.Checked checked = this.views.check(token);
        if (checked.verdict == ViewLinks.Verdict.NOT_OURS) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_VIEW), instance);
        }
        ViewLinks.Link link = checked.link;
        if (checked.verdict == ViewLinks.Verdict.EXPIRED) {
            return CustomerAnswer.problem(Problem.of(410, EXPIRED_VIEW).kind("link-expired"), instance);
        }
        IdentityPort.EmbedClient client;
        try {
            client = this.identity.embedClient(link.tenantId, link.clientId);
        } catch (IdentityPort.Unavailable unavailable) {
            return CustomerAnswer.problem(unavailable(), instance);
        }
        if (!client.active) {
            return CustomerAnswer.problem(client.status == 410 ? Problem.of(410, ENDED_VIEW).kind("link-ended") : Problem.of(404, NO_SUCH_VIEW),
                instance);
        }
        CustomerAnswer answer = RowSecurity.forTenant(link.tenantId, () -> this.transactions.execute(tx -> this.read(link, instance)));
        logger.info("View of run {} of workspace {} (API client {}) read: {}", link.runId, link.tenantId, link.clientId, answer.status);
        return answer;
    }

    private CustomerAnswer read(ViewLinks.Link link, String instance) {
        Optional<CustomerRuns.Found> found = this.runs.found(link.tenantId, link.runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_VIEW), instance);
        }
        CustomerRunStore.Row run = found.get().row;
        CustomerRuns.Steps steps = this.runs.stepsOf(run);
        Map<String, Object> review = this.runs.reviewOf(found.get());
        // The Run, with the pipeline's name, its steps, its made files and its review in full (read-only) beside it.
        Map<String, Object> view = CustomerViews.run(run, (String) review.get("status"));
        view.put("attempt", steps.attempt);
        view.put("pipelineName", found.get().job.getJobName());
        view.put("steps", steps.data);
        List<Map<String, Object>> made = new ArrayList<>();
        for (Map<String, Object> file : this.runs.madeFiles(run, steps.attempt)) {
            Map<String, Object> shown = new LinkedHashMap<>(file);
            if (!Boolean.TRUE.equals(file.get("expired")) && this.files.available()) {
                String fileId = String.valueOf(file.get("id"));
                FileLinks.Issued download = this.files.issue(link.tenantId, fileId, link.clientId, link.expiresAt);
                Map<String, Object> linkView = new LinkedHashMap<>();
                linkView.put("url", "/v1/files/" + fileId + "/content?token=" + download.token);
                linkView.put("expiresAt", ApiTimes.utc(download.expiresAt));
                shown.put("download", linkView);
            } else {
                shown.put("download", null);
            }
            made.add(shown);
        }
        view.put("files", made);
        view.put("review", review);
        view.put("viewExpiresAt", ApiTimes.utc(link.expiresAt));
        view.put("refreshSeconds", SETTLED.contains(String.valueOf(view.get("status"))) ? null : REFRESH_SECONDS);
        return CustomerAnswer.of(200, view, null);
    }

    // ---- GET /v1/embed/runs/{token}/frame ----------------------------------------------------------------------------

    /** Who may frame the page: the console's server asks before it serves /embed/runs/{token}. */
    public static final class Frame {
        public final CustomerAnswer answer;
        /** The frame-ancestors source list: the client's origins, or {@link #NOBODY}. */
        public final String ancestors;

        Frame(CustomerAnswer answer, String ancestors) {
            this.answer = answer;
            this.ancestors = ancestors;
        }
    }

    public Frame frame(String token) {
        String instance = "/v1/embed/runs/{token}/frame";
        ViewLinks.Checked checked = this.views.check(token);
        if (checked.verdict == ViewLinks.Verdict.NOT_OURS) {
            return new Frame(CustomerAnswer.problem(Problem.of(404, NO_SUCH_VIEW), instance), NOBODY);
        }
        ViewLinks.Link link = checked.link;
        IdentityPort.EmbedClient client;
        try {
            client = this.identity.embedClient(link.tenantId, link.clientId);
        } catch (IdentityPort.Unavailable unavailable) {
            return new Frame(CustomerAnswer.problem(unavailable(), instance), NOBODY);
        }
        if (!client.active) {
            return new Frame(CustomerAnswer.problem(Problem.of(404, NO_SUCH_VIEW), instance), NOBODY);
        }
        // An expired link may still be framed where it was allowed, so the portal shows "expired" rather than a broken frame;
        // its data is refused (410) all the same.
        String ancestors = client.frameAncestors.isEmpty() ? NOBODY : String.join(" ", client.frameAncestors);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", checked.verdict == ViewLinks.Verdict.GOOD ? "open" : "expired");
        body.put("frameAncestors", new ArrayList<>(client.frameAncestors));
        body.put("expiresAt", ApiTimes.utc(link.expiresAt));
        return new Frame(CustomerAnswer.of(200, body, null), ancestors);
    }

    private static Problem unavailable() {
        return Problem.of(503, "The view cannot be read right now. Try again in a moment.");
    }
}
