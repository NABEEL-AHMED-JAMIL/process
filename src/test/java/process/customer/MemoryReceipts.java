package process.customer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** ApiReceipts in memory, for the unit tests: the same claim-then-answer rules, no database. */
final class MemoryReceipts extends ApiReceipts {

    final Map<String, Receipt> rows = new LinkedHashMap<>();

    MemoryReceipts() {
        super(null);
    }

    private static String id(long tenantId, String clientId, String key) {
        return tenantId + "|" + clientId + "|" + key;
    }

    @Override
    public Optional<Receipt> find(long tenantId, String clientId, String key) {
        return Optional.ofNullable(this.rows.get(id(tenantId, clientId, key)));
    }

    @Override
    public boolean claim(long tenantId, String clientId, String key, String requestHash) {
        return this.rows.putIfAbsent(id(tenantId, clientId, key), new Receipt(requestHash, null, null, null)) == null;
    }

    @Override
    public void answer(long tenantId, String clientId, String key, int status, String body, String location) {
        Receipt claimed = this.rows.get(id(tenantId, clientId, key));
        this.rows.put(id(tenantId, clientId, key), new Receipt(claimed.requestHash, status, body, location));
    }

    @Override
    public void release(long tenantId, String clientId, String key) {
        Receipt claimed = this.rows.get(id(tenantId, clientId, key));
        if (claimed != null && !claimed.answered()) {
            this.rows.remove(id(tenantId, clientId, key));
        }
    }
}
