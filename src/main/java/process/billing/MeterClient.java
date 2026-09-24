package process.billing;

import com.google.gson.Gson;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The console's line to the metering service.
 *
 * Reports go through platform-commons' MeterReporter (MIG-80), the one every service uses:
 * queued and sent in batches from its own thread, so the request that ran a prompt never waits
 * on the meter; what the meter rejects is parked and what it cannot take is spooled (MIG-15).
 * Reads (usage, subjects, events, the rate card) are synchronous and carry the service key.
 *
 * Not configured (no meter.url) means every report is a no-op and every read answers
 * empty, so the console runs exactly as it did before the meter existed.
 */
@Component
public class MeterClient {

    @Value("${meter.url:}")
    private String url;
    @Value("${meter.service-key:}")
    private String serviceKey;

    private final RestTemplate http;
    private final Gson gson = new Gson();
    /** Null only where there is no meter to report to (a test that builds its own). */
    @Autowired(required = false)
    private MeterReporter reporter;

    public MeterClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.http = new RestTemplate(factory);
    }

    /** For tests: a client pointed at a URL with a key and a reporter, no Spring. */
    public MeterClient(String url, String serviceKey, RestTemplate http, MeterReporter reporter) {
        this.url = url; this.serviceKey = serviceKey; this.http = http; this.reporter = reporter;
    }

    public boolean isConfigured() {
        return this.url != null && !this.url.trim().isEmpty();
    }

    /** Queues one event. Never blocks, never throws. */
    public void report(UsageEvent event) {
        if (this.reporter != null) {
            this.reporter.report(event);
        }
    }

    /** Sends whatever is queued now, synchronously. Tests and shutdown. */
    public void flush() {
        if (this.reporter != null) {
            this.reporter.flush();
        }
    }

    // ---- reads ---------------------------------------------------------------------------------

    public Map<String, Object> usage(Long tenantId, LocalDate start, LocalDate end, String groupBy) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(this.url + "/v1/usage")
            .queryParam("start", start).queryParam("end", end).queryParam("groupBy", groupBy);
        if (tenantId != null) b.queryParam("tenantId", tenantId);
        return this.get(b.toUriString());
    }

    public Map<String, Object> subjects(Long tenantId, String meter, LocalDate start, LocalDate end, int limit) {
        return this.get(UriComponentsBuilder.fromHttpUrl(this.url + "/v1/usage/subjects").queryParam("tenantId", tenantId)
            .queryParam("meter", meter).queryParam("start", start).queryParam("end", end).queryParam("limit", limit).toUriString());
    }

    public Map<String, Object> events(Long tenantId, String meter, LocalDate start, LocalDate end, int page, int limit) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(this.url + "/v1/usage/events").queryParam("tenantId", tenantId)
            .queryParam("page", page).queryParam("limit", limit);
        if (meter != null) b.queryParam("meter", meter);
        if (start != null) b.queryParam("start", start);
        if (end != null) b.queryParam("end", end);
        return this.get(b.toUriString());
    }

    /** The default card in effect today. */
    public Map<String, Object> rateCard() {
        return this.get(this.url + "/v1/ratecard");
    }

    /** One version, whoever it is for. */
    public Map<String, Object> rateCard(int version) {
        return this.get(this.url + "/v1/ratecard?version=" + version);
    }

    /** The card that prices a workspace on a day: its own if it has one in effect, else the default. */
    public Map<String, Object> rateCardFor(Long tenantId, LocalDate day) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(this.url + "/v1/ratecard").queryParam("day", day);
        if (tenantId != null) b.queryParam("tenantId", tenantId);
        return this.get(b.toUriString());
    }

    /** Every version ever saved, newest first. */
    public Map<String, Object> rateCards() {
        return this.get(this.url + "/v1/ratecards");
    }

    /** A new version. Old versions are never changed; the meter answers the saved card. */
    public Map<String, Object> saveRateCard(Map<String, Object> card) {
        ResponseEntity<String> response = this.http.exchange(this.url + "/v1/ratecard", HttpMethod.PUT,
            new HttpEntity<>(this.gson.toJson(card), this.headers()), String.class);
        return this.asMap(response.getBody());
    }

    public Map<String, Object> rollup(int sinceHours) {
        ResponseEntity<String> response = this.http.exchange(this.url + "/v1/rollup?sinceHours=" + sinceHours, HttpMethod.POST,
            new HttpEntity<>("", this.headers()), String.class);
        return this.asMap(response.getBody());
    }

    public Map<String, Object> health() {
        try {
            return this.asMap(this.http.getForObject(this.url + "/health", String.class));
        } catch (RestClientException ex) {
            Map<String, Object> down = new HashMap<>();
            down.put("status", "down");
            down.put("error", ex.getMessage());
            return down;
        }
    }

    private Map<String, Object> get(String uri) {
        if (!this.isConfigured()) {
            return new HashMap<>();
        }
        ResponseEntity<String> response = this.http.exchange(uri, HttpMethod.GET, new HttpEntity<>(this.headers()), String.class);
        return this.asMap(response.getBody());
    }

    /** The meter's JSON as plain maps and lists -- what the console's own serialiser can carry. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return new ObjectMapper().readValue(body, Map.class);
        } catch (IOException ex) {
            throw new IllegalStateException("The meter answered something that is not JSON: " + ex.getMessage());
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Key", this.serviceKey == null ? "" : this.serviceKey);
        return headers;
    }
}
