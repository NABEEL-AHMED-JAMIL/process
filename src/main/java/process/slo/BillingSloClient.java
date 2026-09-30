package process.slo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The billing SLI as Billing measures it from its ledger (MIG-196; billing's MeterSloReport): GET
 * {meter.url}/v1/slo?from=&to= with the meter's service key -- the address and key Core already reports usage with,
 * so nothing new is configured. Core asks only to watch the billing error budget alongside its own (SloBurnAlerts);
 * the definition is Billing's.
 */
@Component
public class BillingSloClient {

    private final RestTemplate http;
    private final String base;
    private final String serviceKey;

    @Autowired
    public BillingSloClient(@Value("${meter.url:}") String meterUrl, @Value("${meter.service-key:}") String serviceKey) {
        this(timeouts(), meterUrl, serviceKey);
    }

    BillingSloClient(RestTemplate http, String meterUrl, String serviceKey) {
        this.http = http;
        this.base = meterUrl == null ? "" : meterUrl.trim().replaceAll("/+$", "");
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
    }

    private static RestTemplate timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    /** Whether Core knows where the meter is: with no meter.url the billing SLI is not watched from here. */
    public boolean configured() {
        return !this.base.isEmpty() && !this.serviceKey.isEmpty();
    }

    /** Good and bad events received in [from, to), as the ledger stands now. Empty when not configured; throws when Billing cannot answer. */
    public Optional<SloBurnRates.Counts> measure(Instant from, Instant to) {
        if (!this.configured()) {
            return Optional.empty();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Key", this.serviceKey);
        String url = UriComponentsBuilder.fromHttpUrl(this.base + "/v1/slo")
            .queryParam("from", from.toString()).queryParam("to", to.toString()).build().toUriString();
        Map<?, ?> body = this.http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
        if (body == null || !(body.get("good") instanceof Number) || !(body.get("bad") instanceof Number)) {
            throw new IllegalStateException("Billing's SLO report had no good and bad counts.");
        }
        return Optional.of(new SloBurnRates.Counts(((Number) body.get("good")).longValue(), ((Number) body.get("bad")).longValue()));
    }
}
