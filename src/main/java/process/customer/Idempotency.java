package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.springframework.stereotype.Component;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Idempotency on every creating POST of the customer API (MIG-332, ADR-025 decision 6), per API client: the
 * Idempotency-Key is required (8 to 128 of [A-Za-z0-9._:-]); the same key with the same request within 24 hours answers
 * the first answer again (Idempotent-Replayed: true) and does nothing; the same key with another request is a 409; a key
 * still being handled is a 409 too. An answer of ours that failed (an exception) frees the key for the retry; a refusal
 * (a 4xx) is an answer like any other and is replayed.
 */
@Component
public class Idempotency {

    private final ApiReceipts receipts;
    private final ObjectMapper json = new ObjectMapper();

    public Idempotency(ApiReceipts receipts) {
        this.receipts = receipts;
    }

    /**
     * The answer to a creating POST: the earlier one for this key, a refusal, or the work's, which is then kept.
     *
     * @param fingerprint what "the same request" means: method, path and body
     */
    public CustomerAnswer once(String key, String instance, String fingerprint, Supplier<CustomerAnswer> work) {
        String refused = IdempotencyKeys.refusal(key);
        if (refused != null) {
            return CustomerAnswer.problem(Problem.of(400, refused), instance);
        }
        long tenantId = TenantContext.getTenantId();
        String clientId = TenantContext.getClientId();
        Optional<CustomerAnswer> earlier = this.earlier(tenantId, clientId, key, fingerprint, instance);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        CustomerAnswer answer;
        try {
            answer = work.get();
        } catch (RuntimeException failed) {
            this.receipts.release(tenantId, clientId, key);
            throw failed;
        }
        if (answer.status >= 500) {
            this.receipts.release(tenantId, clientId, key);
            return answer;
        }
        this.receipts.answer(tenantId, clientId, key, answer.status, this.write(answer.body), answer.location);
        return answer;
    }

    private Optional<CustomerAnswer> earlier(long tenantId, String clientId, String key, String fingerprint, String instance) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ApiReceipts.Receipt> receipt = this.receipts.find(tenantId, clientId, key);
            if (receipt.isPresent()) {
                if (!receipt.get().requestHash.equals(fingerprint)) {
                    return Optional.of(CustomerAnswer.problem(Problem.of(409,
                        "This Idempotency-Key was already used with a different request.").kind("idempotency-conflict"), instance));
                }
                if (!receipt.get().answered()) {
                    return Optional.of(CustomerAnswer.problem(Problem.of(409,
                        "A request with this Idempotency-Key is still being handled. Try again in a moment.")
                        .kind("idempotency-in-progress"), instance));
                }
                return Optional.of(new CustomerAnswer(receipt.get().status, this.read(receipt.get().body), receipt.get().location, true));
            }
            if (this.receipts.claim(tenantId, clientId, key, fingerprint)) {
                return Optional.empty();
            }
        }
        return Optional.of(CustomerAnswer.problem(Problem.of(409, "This Idempotency-Key is in use. Try again in a moment.")
            .kind("idempotency-in-progress"), instance));
    }

    private String write(Map<String, Object> body) {
        try {
            return this.json.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String body) {
        try {
            return this.json.readValue(body.getBytes(StandardCharsets.UTF_8), Map.class);
        } catch (IOException ex) {
            throw new IllegalStateException("A kept answer could not be read.", ex);
        }
    }
}
