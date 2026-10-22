package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import process.correlation.CorrelationInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * MIG-420: the person's masks from the Data Catalog -- analytics-service's GET analyticsCatalog.json/masks, with the
 * signed-in person's own token (as Core registers a form's dataset there, HttpAnalyticsDatasets): the sensitive assets they
 * have no access to, their tagged columns and pattern kinds. Null when it cannot be asked (the caller then fails closed).
 */
@Component
public class CatalogMasks {

    private static final Logger logger = LoggerFactory.getLogger(CatalogMasks.class);

    private final String url;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build();

    public CatalogMasks(@Value("${analytics.url:http://analytics:9140}") String analyticsUrl) {
        this.url = analyticsUrl.replaceAll("/+$", "") + "/api/v1/analyticsCatalog.json/masks";
    }

    /** The masks' data array for the caller of the current request; null when there is no caller or no answer. */
    public JsonNode forCaller() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes
            ? ((ServletRequestAttributes) attributes).getRequest().getHeader("Authorization") : null;
        if (authorization == null || authorization.trim().isEmpty()) {
            return null;
        }
        Request call = new Request.Builder().url(this.url).header("Authorization", authorization).get().build();
        try (Response response = this.http.newCall(call).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (response.code() != 200 || answer == null || !"SUCCESS".equals(answer.path("status").asText())) {
                logger.warn("The Data Catalog's masks could not be read (HTTP {}); item data is masked fail-closed.", response.code());
                return null;
            }
            return answer.path("data");
        } catch (IOException unreachable) {
            logger.warn("The Data Catalog could not be reached for masks ({}); item data is masked fail-closed.", unreachable.getMessage());
            return null;
        }
    }
}
