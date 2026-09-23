package process.storage;

import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * One line per trusted Storage call (MIG-65): who, why, which object, and the correlation id that ties
 * it to the request that caused it -- the gateway's X-Correlation-Id, or "no-request" for a dispatch or
 * startup thread. Never the object's contents.
 */
public final class TrustedStorageAudit {

    private static final Logger audit = LoggerFactory.getLogger(TrustedStorageAudit.class);

    private TrustedStorageAudit() {
    }

    public static void record(String operation, TrustedAccess access, String bucket, String key) {
        audit.info("Trusted storage {} by {} ({}): {}/{} correlationId={}", operation, access.getCaller(),
            access.getReason(), bucket, key, correlationId());
    }

    static String correlationId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes) {
            String header = ((ServletRequestAttributes) attributes).getRequest().getHeader("X-Correlation-Id");
            if (header != null && CorrelationId.isAcceptable(header)) {
                return header;
            }
        }
        String current = CorrelationId.current();
        return current != null ? current : "no-request";
    }
}
