package process.correlation;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * platform-commons' CorrelationIdPropagator for process's OkHttp clients (MIG-94): a call to Media, AI or
 * Storage carries the X-Correlation-Id of the work in hand, so the called service logs under the same id. An id
 * the caller set itself is left alone; outside any work nothing is sent and the service mints its own.
 *
 * Never fails the call (X10). Not for third parties: report export's user-given target, embeddings and
 * OpenSearch are not given it.
 *
 * @author Nabeel Ahmed
 */
public class CorrelationInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(CorrelationInterceptor.class);

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        try {
            String current = CorrelationId.current();
            if (current != null && request.header(CorrelationId.HEADER) == null) {
                request = request.newBuilder().header(CorrelationId.HEADER, current).build();
            }
        } catch (RuntimeException ex) {
            logger.warn("Could not put the correlation id on {} ({}); sending it without", request.url(), ex.toString());
        }
        return chain.proceed(request);
    }
}
