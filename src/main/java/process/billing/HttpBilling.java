package process.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link BillingPort} over billing-service's internal API (X-Internal-Token, the token every internal call carries):
 * POST /internal/billing/month {tenantId} for the zone, POST /internal/billing/runCosts {tenantId, from, to, jobQueueIds}
 * for the runs' costs. Short timeouts: a backfill's preview waits on it, and the zone is cached by {@link WorkspaceZones}.
 */
@Component
public class HttpBilling implements BillingPort {

    /** At most this many runs per question (billing answers each from one period's allocation). */
    public static final int MAX_RUNS = 500;

    private final RestTemplate http;
    private final String base;
    private final String token;

    @Autowired
    public HttpBilling(@Value("${billing.url:http://billing:9130/api/v1}") String billingUrl, @Value("${internal.service-token:}") String token) {
        this(timeouts(), billingUrl, token);
    }

    HttpBilling(RestTemplate http, String billingUrl, String token) {
        this.http = http;
        this.base = billingUrl == null ? "" : billingUrl.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    private static RestTemplate timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }

    @Override
    public String zoneOf(long tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        Map<?, ?> answer = this.post("/internal/billing/month", body);
        Object zone = answer.get("zone");
        if (!(zone instanceof String) || ((String) zone).trim().isEmpty()) {
            throw new BillingUnavailable("billing named no time zone for workspace " + tenantId, null);
        }
        return ((String) zone).trim();
    }

    @Override
    public RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> jobQueueIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("jobQueueIds", new ArrayList<>(jobQueueIds));
        Map<?, ?> answer = this.post("/internal/billing/runCosts", body);
        Map<Long, BigDecimal> costs = new LinkedHashMap<>();
        Object found = answer.get("costs");
        if (found instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) found).entrySet()) {
                try {
                    costs.put(Long.valueOf(String.valueOf(entry.getKey())), new BigDecimal(String.valueOf(entry.getValue())));
                } catch (NumberFormatException unreadable) {
                    // A row billing wrote that is not a run and a cost is not one we asked for.
                }
            }
        }
        return new RunCosts(answer.get("currency") instanceof String ? (String) answer.get("currency") : "USD",
            answer.get("zone") instanceof String ? (String) answer.get("zone") : null, costs);
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        if (this.base.isEmpty() || this.token.isEmpty()) {
            throw new BillingUnavailable("billing is not configured here (billing.url, internal.service-token)", null);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", this.token);
        try {
            Map<?, ?> answer = this.http.postForObject(this.base + path, new HttpEntity<>(body, headers), Map.class);
            if (answer == null) {
                throw new BillingUnavailable("billing answered nothing", null);
            }
            return answer;
        } catch (RestClientException failed) {
            throw new BillingUnavailable("billing could not be asked: " + failed.getMessage(), failed);
        }
    }
}
