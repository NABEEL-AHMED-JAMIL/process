package process.billing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.time.Instant;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The console's line to the metering service.
 *
 * Reports are queued and sent in batches from one background thread, so the request that
 * deleted a file or ran a prompt never waits on the meter; a meter that is down costs a
 * warning and, past the queue's capacity, dropped events -- logged, never thrown. Reads
 * (usage, subjects, events, the rate card) are synchronous and carry the service key.
 *
 * Not configured (no meter.url) means every report is a no-op and every read answers
 * empty, so the console runs exactly as it did before the meter existed.
 */
@Component
public class MeterClient {

    private static final Logger logger = LoggerFactory.getLogger(MeterClient.class);
    static final int BATCH = 500;
    static final int QUEUE_CAPACITY = 50_000;

    @Value("${meter.url:}")
    private String url;
    @Value("${meter.service-key:}")
    private String serviceKey;

    private final RestTemplate http;
    // Instant is serialised as ISO-8601 text: Gson's default reflects into java.time, which a
    // modern JVM refuses, and the meter parses the text form anyway.
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (instant, type, context) -> new JsonPrimitive(instant.toString()))
        .create();
    private final LinkedBlockingQueue<UsageEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private Thread sender;
    private volatile boolean running;
    private long dropped;

    public MeterClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.http = new RestTemplate(factory);
    }

    /** For tests: a client pointed at a URL with a key, no Spring. */
    public MeterClient(String url, String serviceKey, RestTemplate http) {
        this.url = url; this.serviceKey = serviceKey; this.http = http;
    }

    public boolean isConfigured() {
        return this.url != null && !this.url.trim().isEmpty();
    }

    @PostConstruct
    void start() {
        if (!this.isConfigured()) {
            logger.info("meter.url is not set; usage is not reported");
            return;
        }
        this.running = true;
        this.sender = new Thread(this::drain, "meter-sender");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    @PreDestroy
    void stop() {
        this.running = false;
        if (this.sender != null) {
            this.sender.interrupt();
        }
        this.flush();
    }

    /** Queues one event. Never blocks, never throws. */
    public void report(UsageEvent event) {
        if (!this.isConfigured() || event == null || event.tenantId == null || event.quantity == 0) {
            return;
        }
        if (!this.queue.offer(event)) {
            if (this.dropped++ % 1000 == 0) {
                logger.warn("meter: queue full ({}), dropping usage events -- {} dropped so far", QUEUE_CAPACITY, this.dropped);
            }
        }
    }

    private void drain() {
        while (this.running) {
            try {
                UsageEvent first = this.queue.poll(2, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                List<UsageEvent> batch = new ArrayList<>();
                batch.add(first);
                this.queue.drainTo(batch, BATCH - 1);
                this.send(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                logger.warn("meter: sender loop error: {}", ex.toString());
            }
        }
    }

    /** Sends whatever is queued now, synchronously. Tests and shutdown. */
    public int flush() {
        List<UsageEvent> batch = new ArrayList<>();
        this.queue.drainTo(batch);
        int sent = 0;
        for (int i = 0; i < batch.size(); i += BATCH) {
            sent += this.send(batch.subList(i, Math.min(batch.size(), i + BATCH)));
        }
        return sent;
    }

    private int send(List<UsageEvent> batch) {
        Map<String, Object> body = new HashMap<>();
        body.put("events", batch);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ResponseEntity<String> response = this.http.exchange(this.url + "/v1/events", HttpMethod.POST,
                    new HttpEntity<>(this.gson.toJson(body), this.headers()), String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    JsonObject answer = this.gson.fromJson(response.getBody(), JsonObject.class);
                    int rejected = answer.has("rejected") ? answer.getAsJsonArray("rejected").size() : 0;
                    if (rejected > 0) {
                        logger.warn("meter: {} of {} event(s) rejected: {}", rejected, batch.size(), answer.get("rejected"));
                    }
                    return batch.size();
                }
                logger.warn("meter: attempt {} answered {}", attempt, response.getStatusCode());
            } catch (RestClientException ex) {
                logger.warn("meter: attempt {} failed: {}", attempt, ex.getMessage());
            }
            try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
        }
        logger.error("meter: {} usage event(s) could not be delivered and are lost", batch.size());
        return 0;
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
